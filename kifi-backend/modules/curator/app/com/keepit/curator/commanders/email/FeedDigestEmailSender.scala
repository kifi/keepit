package com.keepit.curator.commanders.email

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.concurrent.PimpMyFuture._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.helpers.toHttpsUrl
import com.keepit.common.mail.template.{ EmailTips, EmailToSend }
import com.keepit.common.store.S3UserPictureConfig
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.curator.model.{ UriRecommendation, UriRecommendationRepo }
import com.keepit.curator.queue.SendFeedDigestToUserMessage
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUser, SocialNetworks }
import com.kifi.franz.SQSQueue
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Random, Success }

object DigestEmail {
  val READ_TIMES = (1 to 10) ++ Seq(15, 20, 30, 45, 60)

  // recommendations to actual email to the user
  val MIN_RECOMMENDATIONS_TO_DELIVER = 2
  val MAX_RECOMMENDATIONS_TO_DELIVER = 3

  // fetch additional recommendations in case some are filtered out
  val RECOMMENDATIONS_TO_QUERY = 100

  // exclude recommendations with an image less than this
  val MIN_IMAGE_WIDTH_PX = 535
  val MAX_IMAGE_HEIGHT_PX = 1000

  // max # of friend thumbnails to show for each recommendation
  val MAX_FRIENDS_TO_SHOW = 10

  // the minimum masterScore for a URIRecommendation to make the cut
  val RECO_THRESHOLD = 8
}

sealed case class AllDigestRecos(toUser: Id[User], recos: Seq[DigestReco], isFacebookConnected: Boolean = false)

sealed case class DigestReco(reco: UriRecommendation, uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestRecoKeepers, protected val config: FortyTwoConfig, protected val isForQa: Boolean = false) {
  val title = uriSummary.title.getOrElse(uri.title.getOrElse(""))
  val description = uriSummary.description.getOrElse("")
  val imageUrl = uriSummary.imageUrl.map(toHttpsUrl)
  val url = uri.url
  val domain = DomainToNameMapper.getNameFromUrl(url)
  val score = reco.masterScore
  val explain = reco.allScores.toString
  val topic = reco.attribution.topic.map(_.topicName)
  val readTime = uriSummary.wordCount.filter(_ >= 0).map { wc =>
    val minutesEstimate = wc / 250
    DigestEmail.READ_TIMES.find(minutesEstimate < _).map(_ + " min").getOrElse("> 1 h")
  }

  // todo(josh) encode urls?? add more analytics information
  val viewPageUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/view?id=${uri.externalId}"
  val sendPageUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/send?id=${uri.externalId}"
  val keepUrl = if (isForQa) uri.url else s"${config.applicationBaseUrl}/r/e/1/recos/keep?id=${uri.externalId}"
}

sealed case class KeeperUser(userId: Id[User], avatarUrl: String, basicUser: BasicUser) {
  val firstName = basicUser.firstName
  val lastName = basicUser.lastName
}

sealed case class DigestRecoKeepers(friends: Seq[Id[User]] = Seq.empty, others: Int = 0,
    keepers: Map[Id[User], BasicUser] = Map.empty,
    userAvatarUrls: Map[Id[User], String] = Map.empty) {

  val friendsToShow = keepers.map(_._1)

  val message = {
    // adding s works since we are only dealing with "friend" and "other"
    @inline def pluralize(size: Int, word: String) = size + " " + (if (size == 1) word else word + "s")

    val friendsMsg = if (friends.size > 0) Some(pluralize(friends.size, "friend")) else None
    val othersMsg = if (others > 0) Some(pluralize(others, "other")) else None
    val keepersMessagePrefix = Seq(friendsMsg, othersMsg).flatten.mkString(" and ")
    if (keepersMessagePrefix.size > 0) keepersMessagePrefix + " kept this" else ""
  }
}

sealed case class DigestRecoMail(userId: Id[User], mailSent: Boolean, feed: Seq[DigestReco])

class FeedDigestEmailSender @Inject() (
    recommendationGenerationCommander: RecommendationGenerationCommander,
    uriRecommendationRepo: UriRecommendationRepo,
    shoebox: ShoeboxServiceClient,
    abook: ABookServiceClient,
    db: Database,
    serviceDiscovery: ServiceDiscovery,
    queue: SQSQueue[SendFeedDigestToUserMessage],
    userExperimentCommander: RemoteUserExperimentCommander,
    protected val config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  import com.keepit.curator.commanders.email.DigestEmail._

  val defaultUriRecommendationScores = UriRecommendationScores()

  def addToQueue(): Future[Set[Id[User]]] = {
    if (serviceDiscovery.isLeader()) {
      userExperimentCommander.getUsersByExperiment(ExperimentType.RECOS_BETA).map { userSet =>
        userSet.map { user =>
          if (user.primaryEmail.isDefined) {
            queue.send(SendFeedDigestToUserMessage(user.id.get))
            user.id
          } else {
            log.info(s"NOT sending digest email to ${user.id.get}; primaryEmail missing")
            None
          }
        }.flatten.toSet
      }
    } else {
      airbrake.notify("FeedDigestEmailSender.send() should not be called by non-leader!")
      Future.successful(Set.empty)
    }
  }

  def processQueue(): Future[Unit] = {
    def fetchFromQueue(): Future[Seq[Unit]] = {
      log.info(s"fetching 5 messages from queue ${queue.queue.name}")
      queue.nextBatchWithLock(1, 1 minute).flatMap { messages =>
        log.info(s"locked ${messages.size} messages from queue ${queue.queue.name}")
        Future.sequence(messages.map { message =>
          try {
            sendToUser(message.body.userId).map { digestMail =>
              if (digestMail.mailSent) log.info(s"consumed digest email for ${digestMail.userId}")
              else log.warn(s"digest email was not mailed: $digestMail")
              message.consume()
            } recover {
              case e =>
                airbrake.notify(s"error sending digest email to ${message.body.userId}", e)
            } map (_ => ())
          } catch {
            case e: Throwable =>
              airbrake.notify(s"error sending digest email to ${message.body.userId} before future", e)
              Future.successful(())
          }
        })
      }
    }

    val doneF = FutureHelpers.whilef(fetchFromQueue().map(_.size > 0))()
    doneF.onFailure {
      case e =>
        airbrake.notify(s"SQS queue(${queue.queue.name}) nextBatchWithLock failed", e)
    }

    doneF
  }

  def sendToUser(userId: Id[User]): Future[DigestRecoMail] = {
    log.info(s"sending engagement feed email to $userId")

    val recosF = getDigestRecommendationsForUser(userId)
    val socialInfosF = shoebox.getSocialUserInfosByUserId(userId)

    // todo(josh) add detailed tracking of sent digest emails; abort if another email was sent within N days

    val digestRecoMailF = for {
      recos <- recosF
      socialInfos <- socialInfosF
    } yield {
      if (recos.size >= MIN_RECOMMENDATIONS_TO_DELIVER) composeAndSendEmail(userId, recos, socialInfos)
      else {
        log.info(s"NOT sending digest email to $userId; 0 worthy recos")
        Future.successful(DigestRecoMail(userId = userId, mailSent = false, feed = Seq.empty))
      }
    }
    digestRecoMailF.flatten
  }

  private def composeAndSendEmail(userId: Id[User], digestRecos: Seq[DigestReco],
    socialInfos: Seq[SocialUserInfo]): Future[DigestRecoMail] = {

    val isFacebookConnected = socialInfos.find(_.networkType == SocialNetworks.FACEBOOK).exists(_.getProfileUrl.isDefined)
    val emailData = AllDigestRecos(toUser = userId, recos = digestRecos, isFacebookConnected = isFacebookConnected)

    // TODO(josh) use the inlined template (feedDigestInlined) as soon as the base one is done/approved
    // TODO(josh) add textBody to EmailModule

    val mainTemplate = views.html.email.feedDigest(emailData)

    val emailToSend = EmailToSend(
      category = NotificationCategory.User.DIGEST,
      subject = s"Kifi Digest: ${digestRecos.head.title}",
      to = Left(userId),
      from = SystemEmailAddress.NOTIFICATIONS,
      htmlTemplate = mainTemplate,
      senderUserId = Some(userId),
      fromName = Some(Right("Kifi")),
      campaign = Some("digest"),
      tips = Seq(EmailTips.FriendRecommendations)
    )

    log.info(s"sending email to $userId with ${digestRecos.size} keeps")
    shoebox.processAndSendMail(emailToSend).map { sent =>
      if (sent) {
        db.readWrite { implicit rw =>
          digestRecos.foreach(digestReco => uriRecommendationRepo.incrementDeliveredCount(digestReco.reco.id.get, true))
        }
        sendAnonymoizedEmailToQa(emailToSend, emailData)
      }
      DigestRecoMail(userId, sent, digestRecos)
    }
  }

  private def sendAnonymoizedEmailToQa(module: EmailToSend, emailData: AllDigestRecos): Unit = {
    // these hard-coded userIds are to replace the email references to the recipient user's friends;
    // with an attempt to maintain the same look and feel, of the original email w/o revealing the real users

    // the email template requires real userIds since they used by the EmailTemplateSender to fetch attributes for that user
    val userIds = Seq(1, 3, 9, 48, 61, 100, 567, 2538, 3466, 7100, 7456).map(i => Id[User](i.toLong)).sortBy(_ => Random.nextInt())
    val fakeUser = User(firstName = "Fake", lastName = "User")
    val fakeBasicUser = BasicUser.fromUser(fakeUser)
    val myFakeUserId = userIds.head
    val otherUserIds = userIds.tail

    val qaEmailData = emailData.copy(
      toUser = myFakeUserId,
      recos = emailData.recos.map { reco =>
        val qaFriends = otherUserIds.take(reco.keepers.friends.size)
        val qaKeepers = qaFriends.take(reco.keepers.keepers.size)
        reco.copy(
          isForQa = true,
          reco = reco.reco.copy(userId = myFakeUserId),
          keepers = reco.keepers.copy(
            friends = qaFriends,
            keepers = qaKeepers.map((_, fakeBasicUser)).toMap,
            userAvatarUrls = qaKeepers.map((_, S3UserPictureConfig.defaultImage)).toMap
          )
        )
      }
    )

    val qaEmailToSend = EmailToSend(
      category = NotificationCategory.User.DIGEST_QA,
      subject = s"Kifi Digest: ${emailData.recos.head.title}",
      to = Right(SystemEmailAddress.FEED_QA),
      from = SystemEmailAddress.NOTIFICATIONS,
      htmlTemplate = views.html.email.feedDigest(qaEmailData),
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

  private def getDigestRecommendationsForUser(userId: Id[User]) = {
    getRecommendationsForUser(userId).flatMap { recos =>
      FutureHelpers.findMatching(recos, MAX_RECOMMENDATIONS_TO_DELIVER, isEmailWorthy, getDigestReco)
    }.map { seq => seq.flatten }
  }

  private def isEmailWorthy(recoOpt: Option[DigestReco]) = {
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

  private def getDigestReco(reco: UriRecommendation): Future[Option[DigestReco]] = {
    val uriId = reco.uriId
    val uriF = shoebox.getNormalizedURI(uriId)
    val summariesF = getRecommendationSummaries(uriId)
    val recoKeepersF = getRecoKeepers(reco)

    for {
      uri <- uriF
      summaries <- summariesF
      recoKeepers <- recoKeepersF
      if summaries.isDefinedAt(uriId)
    } yield Some(DigestReco(reco = reco, uri = uri, uriSummary = summaries(uriId), keepers = recoKeepers, config = config))
  } recover {
    case throwable =>
      airbrake.notify(s"failed to load uri reco details for $reco", throwable)
      None
  }

  private def getRecommendationsForUser(userId: Id[User]) = {
    recommendationGenerationCommander.getTopRecommendationsNotPushed(userId, RECOMMENDATIONS_TO_QUERY, RECO_THRESHOLD)
  }

  private def getRecommendationSummaries(uriIds: Id[NormalizedURI]*) = {
    shoebox.getUriSummaries(uriIds)
  }

  private def getRecoKeepers(reco: UriRecommendation) = {
    reco.attribution.user match {
      case Some(userAttribution) if userAttribution.friends.size > 0 =>
        shoebox.getBasicUsers(userAttribution.friends.take(MAX_FRIENDS_TO_SHOW)).map { users =>
          DigestRecoKeepers(friends = userAttribution.friends, others = userAttribution.others, keepers = users)
        }
      case Some(userAttribution) => Future.successful(DigestRecoKeepers(others = userAttribution.others))
      case _ => Future.successful(DigestRecoKeepers())
    }
  }

}
