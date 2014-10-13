package com.keepit.curator.commanders.email

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.PimpMyFuture._
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers.toHttpsUrl
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.curator.commanders.{ SeedAttributionHelper, RecommendationGenerationCommander, SeedIngestionCommander }
import com.keepit.curator.model.{ TopicAttribution, UriRecommendation, UriRecommendationRepo, UserAttribution }
import com.keepit.curator.queue.SendFeedDigestToUserMessage
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.search.{ SearchServiceClient }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.SocialNetworks
import com.kifi.franz.SQSQueue
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Random, Success }
import com.keepit.search.augmentation.{ ItemAugmentationResponse, ItemAugmentationRequest, AugmentableItem }

object DigestEmail {
  val READ_TIMES = (1 to 10) ++ Seq(15, 20, 30, 45, 60)

  // recommendations to actual email to the user
  val MIN_RECOMMENDATIONS_TO_DELIVER = 2
  val MAX_RECOMMENDATIONS_TO_DELIVER = 3

  // fetch additional recommendations in case some are filtered out
  val RECOMMENDATIONS_TO_QUERY = 100

  // exclude recommendations with an image less than this
  val MIN_IMAGE_WIDTH_PX = 535
  val MAX_IMAGE_HEIGHT_PX = 900

  // max # of friend thumbnails to show for each recommendation
  val MAX_FRIENDS_TO_SHOW = 10

  // the minimum masterScore for a URIRecommendation to make the cut
  val RECO_THRESHOLD = 8
}

trait RecoRankStrategy {
  def recommendationsToQuery: Int
  val minRecommendationsToDeliver: Int = 2
  val maxRecommendationsToDeliver: Int = 3
  def ordering: Ordering[UriRecommendation]
}

object GeneralFeedDigestStrategy extends RecoRankStrategy {
  val recommendationsToQuery = 100

  object ordering extends Ordering[UriRecommendation] {
    def compare(a: UriRecommendation, b: UriRecommendation) = ((b.masterScore - a.masterScore) * 1000).toInt
  }
}

object RecentInterestRankStrategy extends RecoRankStrategy {
  val recommendationsToQuery = 1000

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

case class DigestRecoCandidate(uriId: Id[NormalizedURI], topic: Option[TopicAttribution], recommendationId: Id[UriRecommendation],
  userAttribution: Option[UserAttribution]) extends DigestItemCandidate

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

  // todo(josh) encode urls?? add more analytics information
  val viewPageUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/view?id=${uri.externalId}"
  val sendPageUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/send?id=${uri.externalId}"
  val keepUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/keep?id=${uri.externalId}"

  val reasonHeader: Option[Html]
}

case class DigestRecommendationItem(topicOpt: Option[TopicAttribution], recommendationId: Id[UriRecommendation], uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestItemKeepers, protected val config: FortyTwoConfig, protected val isForQa: Boolean = false) extends DigestItem {
  val reasonHeader = topicOpt.map(t => Html(s"Recommended because it’s trending in a topic you’re interested in: ${t.topicName}"))
}

case class DigestLibraryItem(keep: Keep, library: Library, uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestItemKeepers, protected val config: FortyTwoConfig, protected val isForQa: Boolean = false) extends DigestItem {
  val reasonHeader = Some(Html(s"Recommended because it was kept in the library you follow: ${library.name}"))
}

case class DigestItemKeepers(friends: Seq[Id[User]] = Seq.empty, others: Int = 0, friendsToShow: Seq[Id[User]] = Seq.empty) {

  val message = {
    // adding s works since we are only dealing with "friend" and "other"
    @inline def pluralize(size: Int, word: String) = size + " " + (if (size == 1) word else word + "s")

    val friendsMsg = if (friends.size > 0) Some(pluralize(friends.size, "friend")) else None
    val othersMsg = if (others > 0) Some(pluralize(others, "other")) else None
    val keepersMessagePrefix = Seq(friendsMsg, othersMsg).flatten.mkString(" and ")
    if (keepersMessagePrefix.size > 0) keepersMessagePrefix + " kept this" else ""
  }
}

case class DigestMail(userId: Id[User], mailSent: Boolean, recommendations: Seq[DigestRecommendationItem], newKeeps: Seq[DigestLibraryItem] = Seq())

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
    protected val config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  import com.keepit.curator.commanders.email.DigestEmail._

  val defaultUriRecommendationScores = UriRecommendationScores()

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
              if (digestMail.mailSent) log.info(s"[processQueue] consumed digest email for ${digestMail.userId}")
              else log.warn(s"[processQueue] digest email was not mailed: $digestMail")
              message.consume()
            } recover {
              case e => airbrake.notify(s"error sending digest email to ${message.body.userId}", e)
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
    log.info(s"sending engagement feed email to $userId")

    val recosF = getRecommendationsForUser(userId, recoRankStrategy)
    val socialInfosF = shoebox.getSocialUserInfosByUserId(userId)

    // todo(josh) add detailed tracking of sent digest emails; abort if another email was sent within N days

    val digestRecoMailF = for {
      recos <- recosF
      socialInfos <- socialInfosF
      //todo(eishay): find how many new library keeps to show and pull them in.
    } yield {
      val newLibraryItems = Seq[DigestLibraryItem]()
      if (recos.size >= MIN_RECOMMENDATIONS_TO_DELIVER) composeAndSendEmail(userId, recos, newLibraryItems, socialInfos)
      else {
        log.info(s"NOT sending digest email to $userId; 0 worthy recos")
        Future.successful(DigestMail(userId = userId, mailSent = false, recommendations = Seq.empty))
      }
    }
    digestRecoMailF.flatten
  }

  private def composeAndSendEmail(userId: Id[User],
    digestRecos: Seq[DigestRecommendationItem], newLibraryItems: Seq[DigestLibraryItem],
    socialInfos: Seq[SocialUserInfo]): Future[DigestMail] = {

    val isFacebookConnected = socialInfos.find(_.networkType == SocialNetworks.FACEBOOK).exists(_.getProfileUrl.isDefined)
    val emailData = AllDigestItems(toUser = userId, recommendations = digestRecos, newLibraryItems = newLibraryItems, isFacebookConnected = isFacebookConnected)

    val emailToSend = EmailToSend(
      category = NotificationCategory.User.DIGEST,
      subject = s"Kifi Digest: ${digestRecos.headOption.getOrElse(newLibraryItems.head).title}",
      to = Left(userId),
      from = SystemEmailAddress.NOTIFICATIONS,
      htmlTemplate = views.html.email.feedDigest(emailData),
      textTemplate = Some(views.html.email.feedDigest(emailData)),
      senderUserId = Some(userId),
      fromName = Some(Right("Kifi")),
      tips = Seq()
    )

    log.info(s"sending email to $userId with ${digestRecos.size} keeps")
    shoebox.processAndSendMail(emailToSend).map { sent =>
      if (sent) {
        db.readWrite { implicit rw =>
          digestRecos.foreach(digestReco => uriRecommendationRepo.incrementDeliveredCount(digestReco.recommendationId, true))
        }
        sendAnonymoizedEmailToQa(emailToSend, emailData)
      }
      DigestMail(userId, sent, digestRecos)
    }
  }

  private def sendAnonymoizedEmailToQa(module: EmailToSend, emailData: AllDigestItems): Unit = {
    // these hard-coded userIds are to replace the email references to the recipient user's friends;
    // with an attempt to maintain the same look and feel, of the original email w/o revealing the real users

    // the email template requires real userIds since they used by the EmailTemplateSender to fetch attributes for that user
    val userIds = Seq(1, 3, 9, 48, 61, 100, 567, 2538, 3466, 7100, 7456).map(i => Id[User](i.toLong)).sortBy(_ => Random.nextInt())
    val myFakeUserId = userIds.head
    val otherUserIds = userIds.tail

    val qaEmailData = emailData.copy(
      toUser = myFakeUserId,
      recommendations = emailData.recommendations.map { item =>
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
        DigestRecoCandidate(uriId = reco.uriId, topic = reco.attribution.topic, recommendationId = reco.id.get, userAttribution = reco.attribution.user)
      }
      FutureHelpers.findMatching(presortedRecos, rankStrategy.maxRecommendationsToDeliver, isEmailWorthy, getDigestReco)
    } map (_.flatten)
  }

  private def isEmailWorthy(recoOpt: Option[DigestRecommendationItem]) = {
    recoOpt match {
      case Some(reco) =>
        val summary = reco.uriSummary
        val uri = reco.uri
        summary.imageWidth.isDefined && summary.imageUrl.isDefined && summary.imageWidth.get >= MIN_IMAGE_WIDTH_PX &&
          summary.imageHeight.isDefined && summary.imageHeight.get <= MAX_IMAGE_HEIGHT_PX &&
          (summary.title.exists(_.size > 0) || uri.title.exists(_.size > 0))
      case None => false
    }
  }

  private def getDigestReco(candidate: DigestItemCandidate): Future[Option[DigestRecommendationItem]] = {
    val uriId = candidate.uriId
    val uriF = shoebox.getNormalizedURI(uriId)
    val summariesF = getRecommendationSummaries(uriId)

    for {
      uri <- uriF
      summaries <- summariesF
      if summaries.isDefinedAt(uriId)
    } yield {
      val keepers = getRecoKeepers(candidate)
      candidate match {
        case reco: DigestRecoCandidate =>
          Some(DigestRecommendationItem(topicOpt = reco.topic, recommendationId = reco.recommendationId, uri = uri,
            uriSummary = summaries(uriId), keepers = keepers, config = config))
      }
    }
  } recover {
    case throwable =>
      airbrake.notify(s"failed to load uri reco details for $candidate", throwable)
      None
  }

  /**
   * this method assumes that all keeps are in library
   */
  private def getAttributions(userId: Id[User], keeps: Seq[Keep]): Future[Seq[DigestLibraryItemCandidate]] = {
    val request = ItemAugmentationRequest.uniform(userId, keeps.map(_.uriId).map { uriId => AugmentableItem(uriId) }: _*)
    val keepMap = keeps map { k => (k.uriId, k.libraryId.get) -> k } toMap
    val augmentations: Future[ItemAugmentationResponse] = search.augmentation(request)
    augmentations map { augmentations =>
      val userAttributions = augmentations.infos map {
        case (augmentableItem, augmentationInfo) =>
          (augmentableItem.uri -> augmentableItem.keptIn.get) -> seedAttributionHelper.toUserAttribution(augmentationInfo)
      }
      keepMap map {
        case (key, keep) =>
          userAttributions.get(key) map { userAttribution =>
            DigestLibraryItemCandidate(keep, Some(userAttribution))
          } getOrElse DigestLibraryItemCandidate(keep, None)
      } toSeq
    }
  }

  private def filterDigestLibraryItem(candidates: Seq[DigestLibraryItemCandidate], max: Int): Future[Seq[DigestItem]] = {
    FutureHelpers.findMatching(candidates, max, isEmailWorthy, getDigestReco).map(_.flatten)
  }

  //  private def keepsToLibraryDigestItems(userId: Id[User], keeps: Seq[Keep], max: Int): Future[Seq[DigestItem]] = {
  //    getAttributions(userId, keeps) map { candidates =>
  //      filterDigestLibraryItem(candidates, max)
  //    }
  //  }
  //
  private def getRecommendationSummaries(uriIds: Id[NormalizedURI]*) = {
    shoebox.getUriSummaries(uriIds)
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

}
