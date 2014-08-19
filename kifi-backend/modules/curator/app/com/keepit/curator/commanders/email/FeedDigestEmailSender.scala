package com.keepit.curator.commanders.email

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, LargeString }
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

import concurrent.Future

object DigestEmail {
  val READ_TIMES = (1 to 10) ++ Seq(15, 20, 30, 45, 60)
  val RECOMMENDATION_COUNT = 5
  val MAX_FRIENDS_TO_SHOW = 10
}

sealed case class AllDigestRecos(toUser: User, recos: Seq[DigestReco])

sealed case class DigestReco(reco: UriRecommendation, uri: NormalizedURI, uriSummary: URISummary,
    keepers: DigestRecoKeepers) {
  val title = uriSummary.title.getOrElse("")
  val description = uriSummary.description.getOrElse("")
  val imageUrl = uriSummary.imageUrl.map { url => if (url.startsWith("//")) "https:" + url else url }
  val url = uri.url
  val score = reco.masterScore
  val explain = reco.allScores.toString
  val readTime = uriSummary.wordCount.filter(_ >= 0).map { wc =>
    val minutesEstimate = wc / 250
    DigestEmail.READ_TIMES.find(minutesEstimate < _).map(_ + " min").getOrElse("> 1 h")
  }
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
      log.info(s"NOT sending engagement feed email to ${user.id.get}; primaryEmail missing")
      return Future.successful(DigestRecoMail(userId = user.id.get, mailSent = false, Seq.empty))
    }

    val userId = user.id.get
    log.info(s"sending engagement feed email to $userId")

    getDigestRecommendationsForUser(userId).flatMap { recos =>
      composeAndSendEmail(user, recos)
    }
  }

  private def composeAndSendEmail(user: User, digestRecos: Seq[DigestReco]): Future[DigestRecoMail] = {
    val userId = user.id.get
    val emailData = AllDigestRecos(toUser = user, recos = digestRecos)
    val htmlBody: LargeString = views.html.email.feedDigestInlined(emailData).body
    val textBody: Some[LargeString] = Some(views.html.email.feedDigestText(emailData).body)

    val email = ElectronicMail(
      category = NotificationCategory.User.DIGEST,
      subject = "Your Recommended Links from friends on Kifi",
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
    for {
      recos <- getRecommendationsForUser(userId) if recos.size > 0
      uriIds = recos.map(_.uriId)
      summaries <- getRecommendationSummaries(uriIds)
      uris <- shoebox.getNormalizedURIs(uriIds)
      recosKeepers <- getRecoKeepers(recos)
    } yield {
      val uriMap = uris.map(uri => uri.id.get -> uri).toMap
      recos.map { reco =>
        val summary = summaries(reco.uriId)
        val uri = uriMap(reco.uriId)
        val recoKeepers = recosKeepers(reco.uriId)
        DigestReco(reco, uri, summary, recoKeepers)
      }
    }
  }

  private def getRecommendationsForUser(userId: Id[User]) = {
    recommendationGenerationCommander.getTopRecommendationsNotPushed(userId, RECOMMENDATION_COUNT)
  }

  private def getRecommendationSummaries(uriIds: Seq[Id[NormalizedURI]]) = {
    shoebox.getUriSummaries(uriIds)
  }

  private def getRecoKeepers(recos: Seq[UriRecommendation]) = {
    Future.sequence[(Id[NormalizedURI], DigestRecoKeepers), Seq] {
      recos.map { reco =>
        reco.attribution.user.collect {
          case userAttribution if userAttribution.friends.size > 0 =>
            for {
              users <- shoebox.getBasicUsers(userAttribution.friends.take(MAX_FRIENDS_TO_SHOW))
              avatarUrls <- getManyUserImageUrls(users.keys.toSeq: _*)
            } yield {
              reco.uriId -> DigestRecoKeepers(friends = userAttribution.friends,
                others = userAttribution.others, keepers = users, userAvatarUrls = avatarUrls)
            }
          case userAttribution =>
            Future.successful(reco.uriId -> DigestRecoKeepers(others = userAttribution.others))
        } getOrElse Future.successful(reco.uriId -> DigestRecoKeepers())
      }
    }.map(_.toMap)
  }

  private def getManyUserImageUrls(userIds: Id[User]*): Future[Map[Id[User], String]] = {
    val seqF = userIds.map { userId => userId -> shoebox.getUserImageUrl(userId, 100) }
    Future.traverse(seqF) { pair =>
      val (userId, urlF) = pair
      urlF.map((userId, _))
    }.map(_.toMap)
  }

}
