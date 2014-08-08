package com.keepit.curator.commanders.email

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.db.{ Id, LargeString }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.curator.model.RecommendationInfo
import com.keepit.model.{ URISummary, NormalizedURI, User, UriRecommendationScores, NotificationCategory, ExperimentType }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.time.Clock
import org.joda.time.DateTimeZone
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import concurrent.Future

case class RecommendedUriSummary(reco: RecommendationInfo, uri: NormalizedURI, uriSummary: URISummary) {
  val title = uri.title.getOrElse("")
  val url = uri.url
  val score = reco.score
  val explain = reco.explain.getOrElse("")
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
    shoebox: ShoeboxServiceClient,
    clock: Clock,
    protected val airbrake: AirbrakeNotifier) extends EngagementFeedEmailSender with Logging {

  val defaultUriRecommendationScores = UriRecommendationScores()
  val recommendationCount = 20

  def send() = {
    userExperimentCommander.getUsersByExperiment(ExperimentType.DIGEST_EMAIl).flatMap { userSet =>
      Future.sequence(userSet.map { sendToUser(_) }.toSeq)
    }
  }

  def sendToUser(user: User): Future[EngagementFeedSummary] = {
    log.info(s"sending engagement feed email to ${user.id.get}")

    recommendationGenerationCommander.getAdHocRecommendations(user.id.get, recommendationCount, defaultUriRecommendationScores).flatMap[EngagementFeedSummary] { recos =>
      shoebox.getUriSummaries(recos.map(_.uriId)).flatMap[EngagementFeedSummary] { summaries =>
        val dataFutures: Future[Seq[RecommendedUriSummary]] = Future.sequence {
          recos.map { reco =>
            val summary = summaries(reco.uriId)
            val uriFuture = shoebox.getNormalizedURI(reco.uriId)
            uriFuture.map { uri => RecommendedUriSummary(reco, uri, summary) }
          }
        }

        val resultsAndData = dataFutures.flatMap[EngagementFeedSummary] { feedData =>
          val htmlBody: LargeString = views.html.email.feedRecommendationsInlined(feedData).body
          val textBody: Some[LargeString] = Some(views.html.email.feedRecommendationsText(feedData).body)

          val email = ElectronicMail(
            category = NotificationCategory.User.DIGEST,
            subject = "Feed Recommendations: " + clock.now()(DateTimeZone.UTC).toString,
            htmlBody = htmlBody,
            textBody = textBody,
            to = Seq(user.primaryEmail.get),
            from = SystemEmailAddress.ENG
          )
          log.info(s"sending email to ${user.id.get} with ${feedData.size} keeps")
          shoebox.sendMail(email).map { EngagementFeedSummary(user.id.get, _, feedData) }
        }

        resultsAndData
      }
    }
  }

}
