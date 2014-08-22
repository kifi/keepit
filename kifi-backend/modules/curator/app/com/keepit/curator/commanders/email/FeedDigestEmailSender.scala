package com.keepit.curator.commanders.email

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, LargeString }
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.curator.model.{ UriRecommendationRepo, UriRecommendation }
import com.keepit.model.{ URISummary, NormalizedURI, User, UriRecommendationScores, NotificationCategory, ExperimentType }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }
import views.html.email.helpers
import helpers.Context
import com.keepit.common.concurrent.PimpMyFuture._

import concurrent.Future

object DigestEmail {
  val READ_TIMES = (1 to 10) ++ Seq(15, 20, 30, 45, 60)

  // recommendations to actual email to the user
  val RECOMMENDATIONS_TO_DELIVER = 3

  // fetch additional recommendations in case some are filtered out
  val RECOMMENDATIONS_TO_QUERY = 100

  // exclude recommendations with an image less than this
  val MIN_IMAGE_WIDTH_PX = 488

  // max # of friend thumbnails to show for each recommendation
  val MAX_FRIENDS_TO_SHOW = 10
}

sealed case class AllDigestRecos(toUser: User, recos: Seq[DigestReco])

sealed case class DigestReco(reco: UriRecommendation, uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestRecoKeepers) {
  val title = uriSummary.title.getOrElse(uri.title.getOrElse(""))
  val description = uriSummary.description.getOrElse("")
  val imageUrl = uriSummary.imageUrl.map { url => if (url.startsWith("//")) "https:" + url else url }
  val url = uri.url
  val domain = DomainToNameMapper.getNameFromUrl(url)
  val score = reco.masterScore
  val explain = reco.allScores.toString
  val topic = reco.attribution.topic.map(_.topicName)
  val readTime = uriSummary.wordCount.filter(_ >= 0).map { wc =>
    val minutesEstimate = wc / 250
    DigestEmail.READ_TIMES.find(minutesEstimate < _).map(_ + " min").getOrElse("> 1 h")
  }
  val urls = DigestRecoUrls(recoUrl = url)
}

sealed case class KeeperUser(userId: Id[User], userAvatarUrl: String, basicUser: BasicUser) {
  val firstName = basicUser.firstName
  val lastName = basicUser.lastName
  val avatarUrl = if (userAvatarUrl.startsWith("//")) "https:" + userAvatarUrl else userAvatarUrl
}

sealed case class DigestRecoKeepers(friends: Seq[Id[User]] = Seq.empty, others: Int = 0,
    keepers: Map[Id[User], BasicUser] = Map.empty,
    userAvatarUrls: Map[Id[User], String] = Map.empty) {

  val friendsToShow = keepers.map { pair =>
    val (userId, user) = pair
    KeeperUser(userId, userAvatarUrls(userId), user)
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

sealed case class DigestRecoUrls(recoUrl: String) {
  val viewPage = recoUrl
  val sendPage = recoUrl
  val keepAndSeeMore = recoUrl
}

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
    db: Database,
    protected val airbrake: AirbrakeNotifier) extends FeedDigestEmailSender with Logging {

  import DigestEmail._

  val defaultUriRecommendationScores = UriRecommendationScores()

  def send() = {
    userExperimentCommander.getUsersByExperiment(ExperimentType.DIGEST_EMAIl).flatMap { userSet =>
      Future.sequence(userSet.map(sendToUser(_)).toSeq)
    }
  }

  def sendToUser(user: User): Future[DigestRecoMail] = {
    if (user.primaryEmail.isEmpty) {
      log.info(s"NOT sending digest email to ${user.id.get}; primaryEmail missing")
      return Future.successful(DigestRecoMail(userId = user.id.get, mailSent = false, Seq.empty))
    }

    val userId = user.id.get
    log.info(s"sending engagement feed email to $userId")

    val digestRecoMailF = for {
      recos <- getDigestRecommendationsForUser(userId)
      unsubscribeUrl <- shoebox.getUnsubscribeUrlForEmail(user.primaryEmail.get)
    } yield {
      if (recos.size > 0) composeAndSendEmail(user, recos, unsubscribeUrl)
      else {
        log.info(s"NOT sending digest email to ${user.id.get}; 0 worthy recos")
        Future.successful(DigestRecoMail(userId, false, Seq.empty))
      }
    }
    digestRecoMailF.flatten
  }

  private def composeAndSendEmail(user: User, digestRecos: Seq[DigestReco], _unsubscribeUrl: String): Future[DigestRecoMail] = {
    val userId = user.id.get
    val emailData = AllDigestRecos(toUser = user, recos = digestRecos)
    val ctx = Context(campaign = "emailDigest", unsubscribeUrl = _unsubscribeUrl)

    val htmlBody: LargeString = views.html.email.feedDigest(emailData, ctx).body

    // TODO(josh) use the inlined template as soon as the base one is done/approved
    //val htmlBody: LargeString = views.html.email.feedDigestInlined(emailData).body
    val textBody: Some[LargeString] = Some(views.html.email.feedDigestText(emailData).body)

    val email = ElectronicMail(
      category = NotificationCategory.User.DIGEST,
      subject = s"Kifi Daily Digest: ${digestRecos.head.title}",
      htmlBody = htmlBody,
      textBody = textBody,
      to = Seq(user.primaryEmail.get),
      from = SystemEmailAddress.ENG,
      senderUserId = Some(userId)
    )

    log.info(s"sending email to $userId with ${digestRecos.size} keeps")
    val now = currentDateTime
    shoebox.sendMail(email).map { sent =>
      if (sent) {
        db.readWrite { implicit rw =>
          digestRecos.foreach(digestReco => uriRecommendationRepo.save(digestReco.reco.withLastPushedAt(now)))
        }
      }
      DigestRecoMail(userId, sent, digestRecos)
    }
  }

  private def getDigestRecommendationsForUser(userId: Id[User]) = {
    getRecommendationsForUser(userId).flatMap { recos =>
      FutureHelpers.findMatching(recos, RECOMMENDATIONS_TO_DELIVER, isEmailWorthy, getDigestReco)
    }.map { seq => seq.flatten }
  }

  private def isEmailWorthy(recoOpt: Option[DigestReco]) = {
    recoOpt match {
      case Some(reco) =>
        val summary = reco.uriSummary
        val uri = reco.uri
        summary.imageWidth.isDefined && summary.imageUrl.isDefined && summary.imageWidth.get >= MIN_IMAGE_WIDTH_PX &&
          summary.title.exists(_.size > 0) || uri.title.exists(_.size > 0)
      case None => false
    }
  }

  private def getDigestReco(reco: UriRecommendation): Future[Option[DigestReco]] = {
    val uriId = reco.uriId
    for {
      uri <- shoebox.getNormalizedURI(uriId)
      summaries <- getRecommendationSummaries(uriId)
      recoKeepers <- getRecoKeepers(reco)
      if summaries.isDefinedAt(uriId)
    } yield Some(DigestReco(reco, uri, summaries(uriId), recoKeepers))
  } recover { case throwable =>
    airbrake.notify(s"failed to load data for ${reco}", throwable)
    None
  }

  private def getRecommendationsForUser(userId: Id[User]) = {
    recommendationGenerationCommander.getTopRecommendationsNotPushed(userId, RECOMMENDATIONS_TO_QUERY)
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
