package com.keepit.curator.controllers.internal

import akka.actor.Scheduler
import com.google.inject.Inject
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.CuratorServiceController
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.curator.commanders.email.{ RecentInterestRankStrategy, EngagementEmailActor, FeedDigestEmailSender }
import com.keepit.curator.commanders._
import com.keepit.curator.commanders.persona.PersonaRecommendationIngestor
import com.keepit.curator.model.{ RecommendationSubSource, LibraryRecoSelectionParams, LibraryRecoInfo, RecommendationSource, LibraryRecommendation }
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsBoolean, JsValue, JsString, Json }
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
    personaRecoIngestor: PersonaRecommendationIngestor,
    protected val airbrake: AirbrakeNotifier) extends CuratorServiceController with Logging {

  val topScoreRecoStrategy = new TopScoreRecoSelectionStrategy()
  val diverseRecoStrategy = new DiverseRecoSelectionStrategy()

  val nonlinearRecoScoringStrategy = new NonLinearRecoScoringStrategy()

  val topScoreLibraryRecoStrategy = new TopScoreLibraryRecoSelectionStrategy()

  val nonlinearLibraryRecoScoringStrategy = new NonLinearLibraryRecoScoringStrategy(libraryRecoGenCommander.defaultLibraryScoreParams)

  def adHocRecos(userId: Id[User], n: Int) = Action.async { request =>
    recoGenCommander.getAdHocRecommendations(userId, n, request.body.asJson match {
      case Some(json) => json.as[UriRecommendationScores]
      case None => UriRecommendationScores()
    }).map(recos => Ok(Json.toJson(recos)))
  }

  def topRecos(userId: Id[User]) = Action.async(parse.tolerantJson) { request =>
    val source = (request.body \ "source").as[RecommendationSource]
    val subSource = (request.body \ "subSource").as[RecommendationSubSource]
    val more = (request.body \ "more").as[Boolean]
    val recencyWeight = (request.body \ "recencyWeight").as[Float]
    val context = (request.body \ "context").asOpt[String]

    userExperimentCommander.getExperimentsByUser(userId) map { experiments =>
      val sortStrategy =
        if (experiments.contains(ExperimentType.CURATOR_DIVERSE_TOPIC_RECOS)) diverseRecoStrategy
        else topScoreRecoStrategy
      val scoringStrategy = nonlinearRecoScoringStrategy

      val recoResults = recoRetrievalCommander.topRecos(userId, more, recencyWeight, source, subSource, sortStrategy, scoringStrategy, context)

      Ok(Json.toJson(recoResults))
    }
  }

  def topPublicRecos(userId: Option[Long]) = Action { request =>
    Ok(Json.toJson(recoRetrievalCommander.topPublicRecos(userId map Id[User])))
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

  def updateLibraryRecommendationFeedback(userId: Id[User], libraryId: Id[Library]) = Action(parse.tolerantJson) { request =>
    val feedback = request.body.as[LibraryRecommendationFeedback]
    curatorAnalytics.trackUserFeedback(userId, libraryId, feedback)
    val updated = recoFeedbackCommander.updateLibraryRecommendationFeedback(userId, libraryId, feedback)
    Ok(JsBoolean(updated))
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

  def topLibraryRecos(userId: Id[User], limit: Int, context: Option[String]) = Action.async(parse.tolerantJson) { request =>
    log.info(s"topLibraryRecos called userId=$userId limit=$limit")

    userExperimentCommander.getExperimentsByUser(userId) map { experiments =>
      val sortStrategy = topScoreLibraryRecoStrategy
      val scoringStrategy = nonlinearLibraryRecoScoringStrategy

      val recoResults = libraryRecoGenCommander.getTopRecommendations(userId, limit, sortStrategy, scoringStrategy, context)
      val libRecoInfos = recoResults.recos
      log.info(s"topLibraryRecos returning userId=$userId resultCount=${libRecoInfos.size}")
      Ok(Json.toJson(recoResults))
    }
  }

  def refreshLibraryRecos(userId: Id[User], await: Boolean = false) = Action.async(parse.tolerantJson) { request =>
    val selectionParams = request.body.as[Option[LibraryRecoSelectionParams]]
    log.info(s"refreshLibraryRecos called userId=$userId await=$await selectionParams=$selectionParams")
    val precomputeF = libraryRecoGenCommander.precomputeRecommendationsForUser(userId, selectionParams)
    (if (await) precomputeF else Future.successful()) map { _ => Ok }
  }

  def notifyLibraryRecosDelivered(userId: Id[User]) = Action(parse.tolerantJson) { request =>
    log.info(s"[notifyLibraryRecosDelivered] called userId=$userId")
    val libIds = (request.body \ "libraryIds").as[Set[Id[Library]]]
    val source = (request.body \ "source").asOpt[RecommendationSource]
    val subSource = (request.body \ "subSource").asOpt[RecommendationSubSource]

    if (source.isEmpty || subSource.isEmpty) {
      airbrake.notify("[notifyLibraryRecosDelivered] missing source or subSource payload")
      log.warn("[notifyLibraryRecosDelivered] missing source or subSource payload: " + Json.stringify(request.body))
    }

    libraryRecoGenCommander.trackDeliveredRecommendations(userId, libIds,
      source getOrElse RecommendationSource.Unknown,
      subSource getOrElse RecommendationSubSource.Unknown)

    NoContent
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

  def ingestPersonaRecos(userId: Id[User]) = Action.async(parse.tolerantJson) { request =>
    val js = request.body
    val pids = (js \ "personaIds").as[Seq[Id[Persona]]]
    val t1 = System.currentTimeMillis()
    personaRecoIngestor.ingestUserRecosByPersonas(userId, pids).collect {
      case _ =>
        val t2 = System.currentTimeMillis()
        log.info(s"persona reco ingestion: ${(t2 - t1) / 1000f} seconds")
        statsd.timing("curatorController.ingestPersonaRecos", (t2 - t1), 1.0)
        Ok
    }
  }
}
