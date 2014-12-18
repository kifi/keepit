package com.keepit.curator.controllers.internal

import akka.actor.Scheduler
import com.google.inject.Inject
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.CuratorServiceController
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.curator.commanders.email.{ RecentInterestRankStrategy, EngagementEmailActor, FeedDigestEmailSender }
import com.keepit.curator.commanders._
import com.keepit.curator.model.{ LibraryRecoSelectionParams, LibraryRecoInfo, RecommendationClientType, LibraryRecommendation }
import com.keepit.model.{ Library, UserValueName, UriRecommendationFeedback, NormalizedURI, ExperimentType, User, UriRecommendationScores }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsString, Json }
import play.api.mvc.Action

import concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class CuratorController @Inject() (
    shoebox: ShoeboxServiceClient,
    curatorAnalytics: CuratorAnalytics,
    recoGenCommander: RecommendationGenerationCommander,
    libraryRecoGenCommander: LibraryRecommendationGenerationCommander,
    seedCommander: SeedIngestionCommander,
    recoFeedbackCommander: RecommendationFeedbackCommander,
    recoRetrievalCommander: RecommendationRetrievalCommander,
    publicFeedGenCommander: PublicFeedGenerationCommander,
    feedDigestActor: ActorInstance[EngagementEmailActor],
    scheduler: Scheduler,
    feedEmailSender: FeedDigestEmailSender,
    userExperimentCommander: RemoteUserExperimentCommander,
    protected val airbrake: AirbrakeNotifier) extends CuratorServiceController {

  val topScoreRecoStrategy = new TopScoreRecoSelectionStrategy()
  val diverseRecoStrategy = new DiverseRecoSelectionStrategy()

  val defaultRecoScoringStrategy = new DefaultRecoScoringStrategy()
  val nonlinearRecoScoringStrategy = new NonLinearRecoScoringStrategy()

  def adHocRecos(userId: Id[User], n: Int) = Action.async { request =>
    recoGenCommander.getAdHocRecommendations(userId, n, request.body.asJson match {
      case Some(json) => json.as[UriRecommendationScores]
      case None => UriRecommendationScores()
    }).map(recos => Ok(Json.toJson(recos)))
  }

  def topRecos(userId: Id[User]) = Action.async(parse.tolerantJson) { request =>
    val clientType = (request.body \ "clientType").as[RecommendationClientType]
    val more = (request.body \ "more").as[Boolean]
    val recencyWeight = (request.body \ "recencyWeight").as[Float]

    userExperimentCommander.getExperimentsByUser(userId) map { experiments =>
      val sortStrategy =
        if (experiments.contains(ExperimentType.CURATOR_DIVERSE_TOPIC_RECOS)) diverseRecoStrategy
        else topScoreRecoStrategy
      val scoringStrategy =
        if (experiments.contains(ExperimentType.CURATOR_NONLINEAR_SCORING)) nonlinearRecoScoringStrategy
        else defaultRecoScoringStrategy

      Ok(Json.toJson(recoRetrievalCommander.topRecos(userId, more, recencyWeight, clientType, sortStrategy, scoringStrategy)))
    }
  }

  def topPublicRecos() = Action { request =>
    Ok(Json.toJson(recoRetrievalCommander.topPublicRecos()))
  }

  def generalRecos() = Action { request =>
    Ok(Json.toJson(recoRetrievalCommander.generalRecos()))
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]) = Action.async { request =>
    val json = request.body.asJson.get
    val feedback = json.as[UriRecommendationFeedback]
    curatorAnalytics.trackUserFeedback(userId, uriId, feedback) // WARN: this has to happen before next line (due to read/write of UriRecommendationRepo)
    recoFeedbackCommander.updateUriRecommendationFeedback(userId, uriId, feedback).map(update => Ok(Json.toJson(update)))
  }

  def triggerEmailToUser(code: String, userId: Id[User]) = Action.async {
    log.info(s"[triggerEmailToUser] code=$code userId=$userId")
    code match {
      case "feed" =>
        feedEmailSender.sendToUser(userId).map { digestMail =>
          if (digestMail.mailSent) NoContent else BadRequest
        }
      case "feedRecentInterest" =>
        feedEmailSender.sendToUser(userId, RecentInterestRankStrategy).map { digestMail =>
          if (digestMail.mailSent) NoContent else BadRequest
        }
      case _ =>
        airbrake.notify(s"triggerEmailToUser(code=$code userId=$userId) bad code")
        Future.successful(BadRequest)
    }
  }

  def refreshUserRecos(userId: Id[User]) = Action { request =>
    SafeFuture(seedCommander.forceIngestGraphData(userId), Some("Force ingesting Graph Data to refresh Recos"))
    scheduleToSendDigestEmail(userId)
    Ok
  }

  def topLibraryRecos(userId: Id[User], limit: Int) = Action.async { request =>
    log.info(s"topLibraryRecos called userId=$userId limit=$limit")
    libraryRecoGenCommander.getTopRecommendations(userId, limit) map { libRecos =>
      val libRecoInfos = libRecos.map { r => LibraryRecommendation.toLibraryRecoInfo(r) }
      log.info(s"topLibraryRecos returning userId=$userId resultCount=${libRecoInfos.size}")
      Ok(Json.toJson(libRecoInfos))
    }
  }

  def refreshLibraryRecos(userId: Id[User], await: Boolean = false) = Action.async(parse.tolerantJson) { request =>
    val selectionParams = request.body.as[Option[LibraryRecoSelectionParams]]
    log.info(s"refreshLibraryRecos called userId=$userId await=$await selectionParams=$selectionParams")
    val precomputeF = libraryRecoGenCommander.precomputeRecommendationsForUser(userId, selectionParams)
    (if (await) precomputeF else Future.successful()) map { _ => Ok }
  }

  private def scheduleToSendDigestEmail(userId: Id[User]) = {
    val sendEmailF = shoebox.getExperimentsByUserIds(Seq(userId)) flatMap { usersExperiments =>
      if (usersExperiments(userId).contains(ExperimentType.SEND_DIGEST_EMAIL_ON_REFRESH)) {
        shoebox.getUserValue(userId, UserValueName.LAST_DIGEST_EMAIL_SCHEDULED_AT) map { dateTimeStrOpt =>
          dateTimeStrOpt.isEmpty || dateTimeStrOpt.exists { dateTimeStr =>
            DateTimeJsonFormat.reads(JsString(dateTimeStr)).fold(
              valid = { date =>
                val now = DateTimeJsonFormat.writes(currentDateTime).value
                shoebox.setUserValue(userId, UserValueName.LAST_DIGEST_EMAIL_SCHEDULED_AT, now)
                date < currentDateTime.minusHours(24)
              },
              invalid = { errors =>
                val errMsg = errors.map(_._2.map(_.message)).flatten.mkString("; ")
                airbrake.notify(s"invalid UserValue (${UserValueName.LAST_DIGEST_EMAIL_SCHEDULED_AT} = $dateTimeStr): $errMsg")
                false
              }
            )
          }
        }
      } else Future.successful(false)
    }

    sendEmailF.filter(_ == true).foreach { _ =>
      // todo(josh) add a delayed job onto SQS queue
      log.info(s"[refreshUserRecos] scheduled to send digest email to userId=$userId")

      // note: use of scheduler is for internal prototyping
      scheduler.scheduleOnce(10 minutes) {
        val digestRecoMailF = feedEmailSender.sendToUser(userId, RecentInterestRankStrategy)

        digestRecoMailF.onComplete {
          case Success(digestRecoMail) =>
            if (digestRecoMail.mailSent) {
              log.info(s"[refreshUserRecos] digest email sent to userId=$userId")
            } else log.info(s"[refreshUserRecos] digest email NOT sent to userId=$userId")
          case Failure(e) => airbrake.notify(s"refreshUserRecos failed to send to userId=$userId", e)
        }
      }
    }
  }

  def publicFeedStartInitialLoading = Action { request =>
    publicFeedGenCommander.startInitialLoading()
    Ok
  }
}
