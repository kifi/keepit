package com.keepit.curator.commanders.email

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailModule, SystemEmailAddress }
import com.keepit.common.store.S3UserPictureConfig
import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.curator.model.{ UriRecommendationRepo, UriRecommendation }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ SocialNetworks, BasicUser }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }
import views.html.email.helpers
import com.keepit.common.concurrent.PimpMyFuture._

import concurrent.Future
import scala.util.{ Success, Random, Failure }

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

  val FRIEND_RECOMMENDATIONS_TO_QUERY = 20
  val FRIEND_RECOMMENDATIONS_TO_DELIVER = 5

  // the minimum masterScore for a URIRecommendation to make the cut
  val RECO_THRESHOLD = 8

  def toHttpsUrl(url: String) = if (url.startsWith("//")) "https:" + url else url
}

sealed case class FriendReco(userId: Id[User], basicUser: BasicUser, avatarUrl: String)

sealed case class AllDigestRecos(toUser: User, recos: Seq[DigestReco], friendRecos: Seq[FriendReco], isFacebookConnected: Boolean = false)

sealed case class DigestReco(reco: UriRecommendation, uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestRecoKeepers, protected val config: FortyTwoConfig, protected val isForQa: Boolean = false) {
  val title = uriSummary.title.getOrElse(uri.title.getOrElse(""))
  val description = uriSummary.description.getOrElse("")
  val imageUrl = uriSummary.imageUrl.map(DigestEmail.toHttpsUrl)
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

  val friendsToShow = keepers.map { pair =>
    val (userId, user) = pair
    KeeperUser(userId, DigestEmail.toHttpsUrl(userAvatarUrls(userId)), user)
  }

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

@ImplementedBy(classOf[FeedDigestEmailSenderImpl])
trait FeedDigestEmailSender {
  def send(): Future[Seq[DigestRecoMail]]
  def sendToUser(user: User): Future[DigestRecoMail]
}

class FeedDigestEmailSenderImpl @Inject() (
    recommendationGenerationCommander: RecommendationGenerationCommander,
    userExperimentCommander: RemoteUserExperimentCommander,
    uriRecommendationRepo: UriRecommendationRepo,
    shoebox: ShoeboxServiceClient,
    abook: ABookServiceClient,
    db: Database,
    protected val config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends FeedDigestEmailSender with Logging {

  import DigestEmail._

  val defaultUriRecommendationScores = UriRecommendationScores()

  def send() = {
    userExperimentCommander.getUsersByExperiment(ExperimentType.RECOS_BETA).flatMap { userSet =>
      Future.sequence(userSet.map(sendToUser).toSeq)
    }
  }

  def sendToUser(user: User): Future[DigestRecoMail] = {
    if (user.primaryEmail.isEmpty) {
      log.info(s"NOT sending digest email to ${user.id.get}; primaryEmail missing")
      return Future.successful(DigestRecoMail(userId = user.id.get, mailSent = false, Seq.empty))
    }

    val userId = user.id.get
    log.info(s"sending engagement feed email to $userId")

    val recosF = getDigestRecommendationsForUser(userId)
    val friendRecoF = getFriendRecommendationsForUser(userId)
    val socialInfosF = shoebox.getSocialUserInfosByUserId(userId)

    // todo(josh) add detailed tracking of sent digest emails; abort if another email was sent within N days

    val digestRecoMailF = for {
      recos <- recosF
      friendRecos <- friendRecoF
      socialInfos <- socialInfosF
    } yield {
      if (recos.size >= MIN_RECOMMENDATIONS_TO_DELIVER) composeAndSendEmail(user, recos, friendRecos, socialInfos)
      else {
        log.info(s"NOT sending digest email to ${user.id.get}; 0 worthy recos")
        Future.successful(DigestRecoMail(userId = userId, mailSent = false, feed = Seq.empty))
      }
    }
    digestRecoMailF.flatten
  }

  private def composeAndSendEmail(user: User, digestRecos: Seq[DigestReco], friendRecos: Seq[FriendReco],
    socialInfos: Seq[SocialUserInfo]): Future[DigestRecoMail] = {
    val userId = user.id.get

    val isFacebookConnected = socialInfos.find(_.networkType == SocialNetworks.FACEBOOK).exists(_.getProfileUrl.isDefined)
    val emailData = AllDigestRecos(toUser = user, recos = digestRecos, friendRecos = friendRecos, isFacebookConnected = isFacebookConnected)

    // TODO(josh) use the inlined template (feedDigestInlined) as soon as the base one is done/approved
    // TODO(josh) add textBody to EmailModule

    // TODO(josh) send PYMK as a 2nd module ("tip") instead of inline feedDigest
    val module = EmailModule(
      category = NotificationCategory.User.DIGEST,
      subject = s"Kifi Digest: ${digestRecos.head.title}",
      to = Seq(user.primaryEmail.get),
      from = SystemEmailAddress.NOTIFICATIONS,
      htmlContent = Seq(views.html.email.feedDigest(emailData)),
      senderUserId = Some(userId),
      fromName = Some("Kifi")
    )

    log.info(s"sending email to $userId with ${digestRecos.size} keeps")
    shoebox.sendMailModule(module).map { sent =>
      if (sent) {
        db.readWrite { implicit rw =>
          digestRecos.foreach(digestReco => uriRecommendationRepo.incrementDeliveredCount(digestReco.reco.id.get, true))
        }
        sendAnonymoizedEmailToQa(module, emailData)
      }
      DigestRecoMail(userId, sent, digestRecos)
    }
  }

  private def sendAnonymoizedEmailToQa(module: EmailModule, emailData: AllDigestRecos): Unit = {
    def fakeUserId = Id[User](Random.nextInt(Int.MaxValue))
    val fakeUser = User(firstName = "Fake", lastName = "User")
    val fakeBasicUser = BasicUser.fromUser(fakeUser)
    val myFakeUserId = fakeUserId

    val fakeFriendReco = FriendReco(myFakeUserId, fakeBasicUser, S3UserPictureConfig.defaultImage)
    val qaEmailData = emailData.copy(
      friendRecos = emailData.friendRecos.map(_ => fakeFriendReco),
      toUser = fakeUser,
      recos = emailData.recos.map { reco =>
        val qaFriends = reco.keepers.friends.map(_ => fakeUserId)
        reco.copy(
          isForQa = true,
          reco = reco.reco.copy(userId = myFakeUserId),
          keepers = reco.keepers.copy(
            friends = qaFriends,
            keepers = qaFriends.take(reco.keepers.keepers.size).map((_, fakeBasicUser)).toMap,
            userAvatarUrls = qaFriends.take(reco.keepers.keepers.size).map((_, S3UserPictureConfig.defaultImage)).toMap
          )
        )
      }
    )

    val qaModule = EmailModule(
      category = NotificationCategory.User.DIGEST_QA,
      subject = s"Kifi Digest: ${emailData.recos.head.title}",
      to = Seq(SystemEmailAddress.FEED_QA),
      from = SystemEmailAddress.NOTIFICATIONS,
      htmlContent = Seq(views.html.email.feedDigest(qaEmailData)),
      senderUserId = None,
      fromName = Some("Kifi")
    )

    val sendMailF = shoebox.sendMailModule(qaModule)
    sendMailF.onComplete {
      case Success(sent) => if (!sent) airbrake.notify("Failed to cc digest email to feed-qa")
      case Failure(t) => airbrake.notify("Failed to send digest email to feed-qa", t)
    }
  }

  private def getFriendRecommendationsForUser(userId: Id[User]): Future[Seq[FriendReco]] = {
    for {
      userIds <- abook.getFriendRecommendations(userId, offset = 0, limit = FRIEND_RECOMMENDATIONS_TO_QUERY, bePatient = true)
      if userIds.isDefined
      friends <- shoebox.getBasicUsers(userIds.get)
      friendImages <- getManyUserImageUrls(userIds.get: _*)
    } yield {
      val friendRecos = friends.map(pair => FriendReco(pair._1, pair._2, DigestEmail.toHttpsUrl(friendImages(pair._1)))).toSeq
      friendRecos.sortBy { friendReco =>
        /* kifi ghost images should be at the bottom of the list */
        (if (friendReco.avatarUrl.endsWith("/0.jpg")) 1 else -1) * Random.nextInt(Int.MaxValue)
      }.take(FRIEND_RECOMMENDATIONS_TO_DELIVER)
    }
  } recover {
    case throwable =>
      airbrake.notify(s"getFriendRecommendationsForUser($userId) failed", throwable)
      Seq.empty
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
        for {
          users <- shoebox.getBasicUsers(userAttribution.friends.take(MAX_FRIENDS_TO_SHOW))
          avatarUrls <- getManyUserImageUrls(users.keys.toSeq: _*)
        } yield DigestRecoKeepers(friends = userAttribution.friends, others = userAttribution.others,
          keepers = users, userAvatarUrls = avatarUrls)
      case Some(userAttribution) => Future.successful(DigestRecoKeepers(others = userAttribution.others))
      case _ => Future.successful(DigestRecoKeepers())
    }
  }

  private def getManyUserImageUrls(userIds: Id[User]*): Future[Map[Id[User], String]] = {
    val seqF = userIds.map { userId => userId -> shoebox.getUserImageUrl(userId, 100) }
    Future.traverse(seqF) { pair =>
      val (userId, urlF) = pair
      urlF.map((userId, _))
    }.map(_.toMap)
  }

}
