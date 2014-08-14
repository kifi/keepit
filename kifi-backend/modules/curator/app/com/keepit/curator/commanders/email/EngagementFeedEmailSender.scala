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
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }

import concurrent.Future

case class RecommendedEmailData(toUser: User, recos: Seq[RecommendedUriSummary])

case class RecommendedUriSummary(reco: UriRecommendation, uri: NormalizedURI, uriSummary: URISummary) {
  val title = uriSummary.title.getOrElse("")
  val description = uriSummary.description.getOrElse("")
  val imageUrl = uriSummary.imageUrl.map { url => if (url.startsWith("//")) "https:" + url else url }
  val url = uri.url
  val score = reco.masterScore
  val explain = reco.allScores.toString
}

case class EngagementFeedSummary(userId: Id[User], mailSent: Boolean, feed: Seq[RecommendedUriSummary])

@ImplementedBy(classOf[EngagementFeedEmailSenderImpl])
trait EngagementFeedEmailSender {
  def send(): Future[Seq[EngagementFeedSummary]]
  def sendToUser(user: User): Future[EngagementFeedSummary]
}

class EngagementFeedEmailSenderImpl @Inject() (
    recommendationGenerationCommander: RecommendationGenerationCommander,
    userExperimentCommander: RemoteUserExperimentCommander,
    uriRecommendationRepo: UriRecommendationRepo,
    shoebox: ShoeboxServiceClient,
    db: Database,
    protected val airbrake: AirbrakeNotifier) extends EngagementFeedEmailSender with Logging {

  val defaultUriRecommendationScores = UriRecommendationScores()
  val recommendationCount = 5

  def send() = {
    userExperimentCommander.getUsersByExperiment(ExperimentType.DIGEST_EMAIl).flatMap { userSet =>
      Future.sequence(userSet.map { sendToUser(_) }.toSeq)
    }
  }

  def sendToUser(user: User): Future[EngagementFeedSummary] = {
    if (user.primaryEmail.isEmpty) {
      log.info(s"NOT sending engagement feed email to ${user.id.get}; primaryEmail missing")
      return Future.successful(EngagementFeedSummary(userId = user.id.get, mailSent = false, Seq.empty))
    }

    log.info(s"sending engagement feed email to ${user.id.get}")

    recommendationGenerationCommander.getTopRecommendationsNotPushed(user.id.get, recommendationCount).flatMap[EngagementFeedSummary] { recos =>
      shoebox.getUriSummaries(recos.map(_.uriId)).flatMap[EngagementFeedSummary] { summaries =>
        val dataFutures: Future[Seq[RecommendedUriSummary]] = Future.sequence {
          recos.map { reco =>
            val summary = summaries(reco.uriId)
            val uriFuture = shoebox.getNormalizedURI(reco.uriId)
            uriFuture.map { uri => RecommendedUriSummary(reco, uri, summary) }
          }
        }

        val resultsAndData = dataFutures.flatMap[EngagementFeedSummary] { feedData =>
          val emailData = RecommendedEmailData(toUser = user, recos = feedData)
          val htmlBody: LargeString = views.html.email.feedRecommendationsInlined(emailData).body
          val textBody: Some[LargeString] = Some(views.html.email.feedRecommendationsText(emailData).body)

          val email = ElectronicMail(
            category = NotificationCategory.User.DIGEST,
            subject = "Your Recommended Links from friends on Kifi",
            htmlBody = htmlBody,
            textBody = textBody,
            to = Seq(user.primaryEmail.get),
            from = SystemEmailAddress.ENG,
            senderUserId = Some(user.id.get)
          )

          log.info(s"sending email to ${user.id.get} with ${feedData.size} keeps")
          val now = currentDateTime
          shoebox.sendMail(email).map { sent =>
            if (sent) {
              db.readWrite { implicit rw =>
                recos.foreach { reco => uriRecommendationRepo.save(reco.withLastPushedAt(now)) }
              }
            }
            EngagementFeedSummary(user.id.get, sent, feedData)
          }
        }

        resultsAndData
      }
    }
  }

}
