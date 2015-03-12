package com.keepit.curator.commanders.email

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.PimpMyFuture._
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.helpers.{ libraryName, toHttpsUrl, trackingParam }
import com.keepit.common.mail.template.{ EmailToSend, EmailTrackingParam }
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.curator.commanders.{ CuratorAnalytics, RecommendationGenerationCommander, SeedAttributionHelper, SeedIngestionCommander }
import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource, UriRecommendation, UriRecommendationRepo, UserAttribution }
import com.keepit.curator.queue.SendFeedDigestToUserMessage
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ ExperimentType, SocialUserInfo, NotificationCategory, User, Library, URISummary, Keep, NormalizedURI }
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ AugmentableItem }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.SocialNetworks
import com.kifi.franz.SQSQueue
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Random, Success }

object DigestEmail {
  // the possible # of minutes to show in the email (1..10, 15, 20, 30, etc)
  val READ_TIMES = (1 to 10) ++ Seq(15, 20, 30, 45, 60)

  val LIBRARY_KEEPS_TO_FETCH = 20

  // max # of total digest items to include in the email
  val MIN_KEEPS_TO_DELIVER = 2
  val MAX_KEEPS_TO_DELIVER = 5

  // exclude recommendations with an image less than this
  val MIN_IMAGE_WIDTH_PX = 535
  val MAX_IMAGE_HEIGHT_PX = 900

  // max # of friend thumbnails to show for each recommendation
  val MAX_FRIENDS_TO_SHOW = 10

  // the minimum masterScore for a URIRecommendation to make the cut
  val RECO_THRESHOLD = 8
}

trait RecoRankStrategy {
  def name: String
  def recommendationsToQuery: Int
  val minRecommendationsToDeliver: Int = 2
  val maxRecommendationsToDeliver: Int = 3
  def ordering: Ordering[UriRecommendation]
}

object GeneralFeedDigestStrategy extends RecoRankStrategy {
  val recommendationsToQuery = 100
  val name = "general"

  object ordering extends Ordering[UriRecommendation] {
    def compare(a: UriRecommendation, b: UriRecommendation) = ((b.masterScore - a.masterScore) * 1000).toInt
  }
}

object RecentInterestRankStrategy extends RecoRankStrategy {
  val recommendationsToQuery = 1000
  val name = "recentInterest"

  object ordering extends Ordering[UriRecommendation] {
    def compare(a: UriRecommendation, b: UriRecommendation) = {
      val res = ((b.allScores.recentInterestScore - a.allScores.recentInterestScore) * 1000).toInt
      if (res == 0) GeneralFeedDigestStrategy.ordering.compare(a, b)
      else res
    }
  }
}

case class AllDigestItems(toUser: Id[User], recommendations: Seq[DigestRecommendationItem],
  newLibraryItems: Seq[DigestLibraryItem], isFacebookConnected: Boolean = false)

trait DigestItemCandidate {
  val uriId: Id[NormalizedURI]
  val userAttribution: Option[UserAttribution]
}

case class DigestRecoCandidate(sourceReco: UriRecommendation, uriId: Id[NormalizedURI]) extends DigestItemCandidate {
  val topic = sourceReco.attribution.topic
  val recommendationId = sourceReco.id.get
  val userAttribution = sourceReco.attribution.user
}

case class DigestLibraryItemCandidate(keep: Keep, userAttribution: Option[UserAttribution]) extends DigestItemCandidate {
  val uriId = keep.uriId
}

trait DigestItem {
  val uri: NormalizedURI
  val uriSummary: URISummary
  val keepers: DigestItemKeepers
  protected val config: FortyTwoConfig
  protected val isForQa: Boolean
  val title: String = uriSummary.title.getOrElse(uri.title.getOrElse(""))
  val description = uriSummary.description.getOrElse("")
  val imageUrl = uriSummary.imageUrl.map(toHttpsUrl)
  val url = uri.url
  val domain = DomainToNameMapper.getNameFromUrl(url)
  val readTime = uriSummary.wordCount.filter(_ >= 0).map { wc =>
    val minutesEstimate = wc / 250
    DigestEmail.READ_TIMES.find(minutesEstimate < _).map(_ + " min").getOrElse("> 1 h")
  }

  def viewPageUrl(content: String): Html =
    if (isForQa) Html(uri.url)
    else urlWithTracking(s"${config.applicationBaseUrl}/r/e/1/recos/view?id=${uri.externalId}", content)

  val sendPageUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/send?id=${uri.externalId}"
  val keepUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/keep?id=${uri.externalId}"

  val reasonHeader: Option[Html]

  private def urlWithTracking(url: String, content: String) = Html(s"$url&${EmailTrackingParam.paramName}=${trackingParam(content)}")
}

case class DigestRecommendationItem(uriRecommendation: UriRecommendation, uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestItemKeepers, protected val config: FortyTwoConfig, protected val isForQa: Boolean = false) extends DigestItem {
  val reasonHeader = uriRecommendation.attribution.topic.map { t =>
    Html(s"Recommended because it’s about a topic you are interested in: ${t.topicName}")
  }
}

case class DigestLibraryItem(libraryId: Id[Library], uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestItemKeepers, protected val config: FortyTwoConfig, protected val isForQa: Boolean = false) extends DigestItem {
  val reasonHeader = Some(views.html.email.partials.digestLibraryHeader(libraryId))
}

case class DigestItemKeepers(friends: Seq[Id[User]] = Seq.empty, others: Int = 0, friendsToShow: Seq[Id[User]] = Seq.empty) {

  val messageOpt = {
    // adding s works since we are only dealing with "friend" and "other"
    @inline def pluralize(size: Int, word: String) = size + " " + (if (size == 1) word else word + "s")

    val friendsMsg = if (friends.size > 0) Some(pluralize(friends.size, "connection")) else None
    val othersMsg = if (others > 0) Some(pluralize(others, "other")) else None
    val keepersMessagePrefix = Seq(friendsMsg, othersMsg).flatten.mkString(" and ")
    if (keepersMessagePrefix.size > 0) Some(keepersMessagePrefix + " kept this") else None
  }
}

case class DigestMail(userId: Id[User], mailSent: Boolean, recommendations: Seq[DigestRecommendationItem], newKeeps: Seq[DigestLibraryItem])

@Singleton
class FeedDigestEmailSender @Inject() (
    recommendationGenerationCommander: RecommendationGenerationCommander,
    uriRecommendationRepo: UriRecommendationRepo,
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    db: Database,
    search: SearchServiceClient,
    serviceDiscovery: ServiceDiscovery,
    seedAttributionHelper: SeedAttributionHelper,
    queue: SQSQueue[SendFeedDigestToUserMessage],
    curatorAnalytics: CuratorAnalytics,
    userExperimentCommander: RemoteUserExperimentCommander,
    protected val config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  import com.keepit.curator.commanders.email.DigestEmail._

  private val queueLock = new ReactiveLock(10)

  def addToQueue(additionalUsers: Seq[Id[User]] = Seq.empty): Future[Unit] = {
    if (serviceDiscovery.isLeader()) {
      val usersIdsToSendTo = seedCommander.getUsersWithSufficientData().toSeq ++ additionalUsers

      val seqF = usersIdsToSendTo.map { userId =>
        queueLock.withLockFuture { queue.send(SendFeedDigestToUserMessage(userId)) }
      }

      Future.sequence(seqF) map (_ -> ())
    } else {
      airbrake.notify("FeedDigestEmailSender.send() should not be called by non-leader!")
      Future.successful(())
    }
  }

  def processQueue(): Future[Unit] = {
    def fetchFromQueue(): Future[Boolean] = {
      log.info(s"[processQueue] fetching message from queue ${queue.queue.name}")
      queue.nextWithLock(1 minute).flatMap { messageOpt =>
        messageOpt map { message =>
          try {
            sendToUser(message.body.userId) map { digestMail =>
              if (digestMail.mailSent) log.info(s"[processQueue] consumed digest email userId=${digestMail.userId}")
              else log.warn(s"[processQueue] digest email was not mailed: $digestMail")
              message.consume()
            } recover {
              case e =>
                airbrake.notify(s"error sending digest email to ${message.body.userId}", e)
            } map (_ => true)
          } catch {
            case e: Throwable =>
              airbrake.notify(s"error sending digest email to ${message.body.userId} before future", e)
              Future.successful(true)
          }
        } getOrElse Future.successful(false)
      }
    }

    val doneF = FutureHelpers.whilef(fetchFromQueue())()
    doneF.onFailure {
      case e => airbrake.notify(s"SQS queue(${queue.queue.name}) nextWithLock failed", e)
    }

    doneF
  }

  def sendToUser(userId: Id[User], recoRankStrategy: RecoRankStrategy = GeneralFeedDigestStrategy): Future[DigestMail] = {
    log.info(s"sendToUser userId=$userId recoRankStrategy=${recoRankStrategy.name}")

    val recosF = getRecommendationsForUser(userId, recoRankStrategy)
    val socialInfosF = shoebox.getSocialUserInfosByUserId(userId)

    // todo(josh) add detailed tracking of sent digest emails; abort if another email was sent within N days
    val digestRecoMailF = for {
      recos <- recosF
      socialInfos <- socialInfosF
      keepsFromLibrary <- getKeepsForLibrary(userId = userId, max = MAX_KEEPS_TO_DELIVER - recos.size, exclude = recos.map(_.uri.id.get).toSet)
    } yield {
      val totalItems = recos.size + keepsFromLibrary.size
      if (totalItems >= MIN_KEEPS_TO_DELIVER) composeAndSendEmail(userId, recos, keepsFromLibrary, socialInfos)
      else {
        log.info(s"NOT sending digest email: userId=$userId recos=${recos.size} libraryKeeps=${keepsFromLibrary.size} total=$totalItems")
        Future.successful(DigestMail(userId = userId, mailSent = false, recommendations = Seq.empty, newKeeps = Seq.empty))
      }
    }
    digestRecoMailF.flatten
  }

  private def composeAndSendEmail(userId: Id[User], digestRecos: Seq[DigestRecommendationItem], newLibraryItems: Seq[DigestLibraryItem],
    socialInfos: Seq[SocialUserInfo]): Future[DigestMail] = {

    val isFacebookConnected = socialInfos.find(_.networkType == SocialNetworks.FACEBOOK).exists(_.getProfileUrl.isDefined)
    val emailData = AllDigestItems(toUser = userId, recommendations = digestRecos, newLibraryItems = newLibraryItems, isFacebookConnected = isFacebookConnected)

    val subject = if (currentDateTime.getDayOfWeek() > 5) "Things you should read this weekend" else s"Kifi Digest: ${digestRecos.headOption.getOrElse(newLibraryItems.head).title}"

    val htmlBody = views.html.email.feedDigest(emailData)
    val emailToSend = EmailToSend(
      category = NotificationCategory.User.DIGEST,
      subject = subject,
      to = Left(userId),
      from = SystemEmailAddress.NOTIFICATIONS,
      htmlTemplate = htmlBody,
      textTemplate = Some(views.html.email.feedDigest(emailData)),
      senderUserId = Some(userId),
      fromName = Some(Right("Kifi")),
      tips = Seq()
    )

    log.info(s"Sending Digest email: userId=$userId recos=${digestRecos.size} libraryKeeps=${newLibraryItems.size} isFacebookConnected=$isFacebookConnected")
    shoebox.processAndSendMail(emailToSend).map { sent =>
      if (sent) {
        db.readWrite { implicit rw =>
          digestRecos.foreach(digestReco => uriRecommendationRepo.incrementDeliveredCount(digestReco.uriRecommendation.id.get, withLastPushedAt = true))
          curatorAnalytics.trackDeliveredItems(digestRecos.map(_.uriRecommendation), Some(RecommendationSource.Email))
        }
        sendAnonymoizedEmailToQa(emailToSend, emailData)
      } else {
        val recoIds = digestRecos.map(_.uri.id.get).mkString(",")
        val libKeepIds = newLibraryItems.map(_.uri.id.get).mkString(",")
        log.warn(s"sendToUser failed to send digest email to userId=$userId htmlBodySize=${htmlBody.body.size} recos=$recoIds libraryKeeps=$libKeepIds")
      }
      DigestMail(userId = userId, mailSent = sent, recommendations = digestRecos, newKeeps = newLibraryItems)
    }
  }

  private def sendAnonymoizedEmailToQa(module: EmailToSend, emailData: AllDigestItems): Unit = {
    // these hard-coded userIds are to replace the email references to the recipient user's friends;
    // with an attempt to maintain the same look and feel, of the original email w/o revealing the real users

    // the email template requires real userIds since they used by the EmailTemplateSender to fetch attributes for that user
    val userIds = Seq(1, 3, 9, 48, 61, 100, 2538, 3466, 7100, 7456).map(i => Id[User](i.toLong)).sortBy(_ => Random.nextInt())
    val myFakeUserId = userIds.head
    val otherUserIds = userIds.tail

    val qaEmailData = emailData.copy(
      toUser = myFakeUserId,
      recommendations = emailData.recommendations.map { item =>
        val qaFriends = otherUserIds.take(item.keepers.friends.size)
        val qaKeepers = qaFriends.take(item.keepers.friendsToShow.size)
        item.copy(isForQa = true, keepers = item.keepers.copy(friends = qaFriends, friendsToShow = qaKeepers))
      },
      newLibraryItems = emailData.newLibraryItems.map { item =>
        val qaFriends = otherUserIds.take(item.keepers.friends.size)
        val qaKeepers = qaFriends.take(item.keepers.friendsToShow.size)
        item.copy(isForQa = true, keepers = item.keepers.copy(friends = qaFriends, friendsToShow = qaKeepers))
      }
    )

    val qaEmailToSend = EmailToSend(
      category = NotificationCategory.User.DIGEST_QA,
      subject = s"Kifi Digest: ${emailData.recommendations.headOption.getOrElse(emailData.newLibraryItems.head).title}",
      to = Right(SystemEmailAddress.FEED_QA),
      from = SystemEmailAddress.NOTIFICATIONS,
      htmlTemplate = views.html.email.feedDigest(qaEmailData),
      textTemplate = Some(views.html.email.feedDigest(qaEmailData)),
      senderUserId = None,
      fromName = Some(Right("Kifi")),
      campaign = Some("digestQA")
    )

    val sendMailF = shoebox.processAndSendMail(qaEmailToSend)
    sendMailF.onComplete {
      case Success(sent) => if (!sent) airbrake.notify("Failed to cc digest email to feed-qa")
      case Failure(t) => airbrake.notify("Failed to send digest email to feed-qa", t)
    }
  }

  private def getRecommendationsForUser(userId: Id[User], rankStrategy: RecoRankStrategy): Future[Seq[DigestRecommendationItem]] = {
    val uriRecosF = recommendationGenerationCommander.getTopRecommendationsNotPushed(userId, rankStrategy.recommendationsToQuery, RECO_THRESHOLD)
    uriRecosF flatMap { recos =>
      val presortedRecos = recos.sorted(rankStrategy.ordering).map { reco =>
        DigestRecoCandidate(uriId = reco.uriId, sourceReco = reco)
      }
      FutureHelpers.findMatching(presortedRecos, rankStrategy.maxRecommendationsToDeliver, isEmailWorthy, transformRecoCandidate)
    } map (_.flatten)
  }

  private def isEmailWorthy(recoOpt: Option[DigestItem]) = {
    recoOpt match {
      case Some(reco) =>
        val summary = reco.uriSummary
        val uri = reco.uri
        summary.imageWidth.isDefined && summary.imageUrl.isDefined && summary.imageWidth.get >= MIN_IMAGE_WIDTH_PX &&
          summary.imageHeight.isDefined && summary.imageHeight.get <= MAX_IMAGE_HEIGHT_PX &&
          (summary.title.exists(_.size > 0) || uri.title.exists(_.size > 0)) &&
          summary.description.exists(_.size > 20) &&
          !uri.url.endsWith(".pdf")
      case None => false
    }
  }

  private case class DigestItemDetails(uri: NormalizedURI, summary: URISummary, keepers: DigestItemKeepers)

  private def getDigestReco[S <: DigestItemCandidate, T <: DigestItem](candidate: S)(transform: DigestItemDetails => Option[T]): Future[Option[T]] = {
    val uriId = candidate.uriId
    val uriF = shoebox.getNormalizedURI(uriId)
    val summariesF = shoebox.getUriSummaries(Seq(uriId))

    uriF flatMap (uri => summariesF map { summaries =>
      summaries.get(uriId) flatMap { uriSummary =>
        val keepers = getRecoKeepers(candidate)
        transform(DigestItemDetails(uri, uriSummary, keepers))
      } orElse {
        airbrake.notify(s"failed to load URISummary for uriId=$uriId")
        None
      }
    })
  }

  /**
   * this method assumes that all keeps are in library
   */
  private def getLibraryKeepAttributions(userId: Id[User], keeps: Seq[Keep]): Future[Seq[DigestLibraryItemCandidate]] = {
    search.augment(Some(userId), false, maxKeepersShown = 20, maxLibrariesShown = 15, maxTagsShown = 0, items = keeps.map(keep => AugmentableItem(keep.uriId, keep.libraryId))) map { infos =>
      (keeps zip infos).map {
        case (keep, info) =>
          DigestLibraryItemCandidate(keep, Some(seedAttributionHelper.toUserAttribution(info)))
      }
    }
  }

  private def getKeepsForLibrary(userId: Id[User], max: Int, exclude: Set[Id[NormalizedURI]]): Future[Seq[DigestLibraryItem]] = {
    userExperimentCommander.getExperimentsByUser(userId) flatMap { experiments =>
      if (experiments.contains(ExperimentType.LIBRARIES)) {
        for {
          keeps <- shoebox.newKeepsInLibraryForEmail(userId, LIBRARY_KEEPS_TO_FETCH)
          dedupedKeeps = keeps.filterNot(c => exclude.contains(c.uriId))
          candidates <- getLibraryKeepAttributions(userId, dedupedKeeps)
          digestLibraryItems <- FutureHelpers.findMatching(candidates, max, isEmailWorthy, transformLibraryCandidate).map(_.flatten)
        } yield digestLibraryItems
      } else Future.successful(Seq.empty)
    }
  }

  private def getRecoKeepers(candidate: DigestItemCandidate): DigestItemKeepers = {
    candidate.userAttribution match {
      case Some(userAttribution) if userAttribution.friends.size > 0 =>
        DigestItemKeepers(friends = userAttribution.friends, others = userAttribution.others,
          friendsToShow = userAttribution.friends.take(MAX_FRIENDS_TO_SHOW))
      case Some(userAttribution) => DigestItemKeepers(others = userAttribution.others)
      case _ => DigestItemKeepers()
    }
  }

  private def transformRecoCandidate(candidate: DigestRecoCandidate): Future[Option[DigestRecommendationItem]] =
    getDigestReco(candidate) { info =>
      Some(DigestRecommendationItem(uriRecommendation = candidate.sourceReco, uri = info.uri,
        uriSummary = info.summary, keepers = info.keepers, config = config))
    }

  private def transformLibraryCandidate(candidate: DigestLibraryItemCandidate): Future[Option[DigestLibraryItem]] =
    getDigestReco(candidate) { info =>
      candidate.keep.libraryId map { libId =>
        DigestLibraryItem(libraryId = libId, uri = info.uri, uriSummary = info.summary, keepers = info.keepers, config = config)
      }
    }

}
