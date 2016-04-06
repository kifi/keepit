package com.keepit.slack

import com.google.inject.Inject
import com.keepit.slack.models.SlackErrorCode._
import com.keepit.commanders.{ OrganizationInfoCommander, KeepInterner, PermissionCommander, RawBookmarkRepresentation }
import com.keepit.common.akka.{ SafeFuture, FortyTwoActor }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, UrlClassifier }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.models.SlackIntegration.{ BrokenSlackIntegration, ForbiddenSlackIntegration }
import com.keepit.slack.models._
import com.kifi.juggle._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.{ Duration, Period }
import play.api.libs.json.{ JsValue, Json }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object KifibotFeedbackConfig {
  val delayUntilNextIngestion = Duration.standardHours(12)

  val maxProcessingDuration = Duration.standardMinutes(5)
  val minConcurrency = 1
  val maxConcurrency = 5

  val messagesPerRequest = 10
  val messagesPerIngestion = 50
}

class KifibotFeedbackActor @Inject() (
  db: Database,
  schedulingRepo: KifibotFeedbackRepo,
  slackChannelRepo: SlackChannelRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  slackTeamRepo: SlackTeamRepo,
  permissionCommander: PermissionCommander,
  libraryRepo: LibraryRepo,
  slackClient: SlackClientWrapper,
  urlClassifier: UrlClassifier,
  keepInterner: KeepInterner,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  slackOnboarder: SlackOnboarder,
  orgConfigRepo: OrganizationConfigurationRepo,
  userValueRepo: UserValueRepo,
  implicit val ec: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[KifibotFeedback]] {

  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)
  import KifibotFeedbackConfig._

  protected val minConcurrentTasks = minConcurrency
  protected val maxConcurrentTasks = maxConcurrency

  protected def pullTasks(limit: Int): Future[Seq[Id[KifibotFeedback]]] = SafeFuture {
    val now = clock.now
    db.readWrite { implicit session =>
      val candidates = schedulingRepo.getRipeForProcessing(limit, now minus maxProcessingDuration)
      candidates.filter(id => schedulingRepo.markAsProcessing(id, now minus maxProcessingDuration))
    }
  }

  protected def processTasks(ids: Seq[Id[KifibotFeedback]]): Map[Id[KifibotFeedback], Future[Unit]] = {
    val (modelById, kifibotByTeam) = db.readOnlyMaster { implicit session =>
      val modelById = schedulingRepo.getByIds(ids.toSet)
      val kifibotByTeam = {
        val teamIds = modelById.values.map(_.slackTeamId).toSet
        slackTeamRepo.getBySlackTeamIds(teamIds).flatMapValues(_.kifiBot)
      }
      (modelById, kifibotByTeam)
    }
    modelById.map {
      case (id, model) =>
        id -> ingestFeedbackIfPossibleAndReschedule(model, kifibotByTeam.get(model.slackTeamId))
    }
  }

  private def ingestFeedbackIfPossibleAndReschedule(model: KifibotFeedback, kifiBotOpt: Option[KifiSlackBot]): Future[Unit] = {
    slackLog.info("Figuring out if we want to ingest feedback from", model.slackUserId.value, "via", kifiBotOpt.toString)
    val actualIngestion = kifiBotOpt.fold(Future.successful(()))(kifiBot => ingestNewFeedback(model, kifiBot))
    actualIngestion andThen {
      case result =>
        val now = clock.now
        db.readWrite { implicit session => schedulingRepo.finishProcessing(model.id.get, now plus delayUntilNextIngestion) }
        result match {
          case Success(_) => slackLog.info("Successfully ingested feedback from", model.slackUserId.value, "in", model.slackTeamId.value)
          case Failure(fail) => slackLog.info("Failed to ingest feedback from", model.slackUserId.value, "in", model.slackTeamId.value, "because", fail.getMessage)
        }
    }
  }

  private def ingestNewFeedback(model: KifibotFeedback, kifibot: KifiSlackBot): Future[Unit] = {
    getNewFeedbackMessages(model, kifibot.token, limit = messagesPerIngestion).map { msgs =>
      val feedback = msgs.filter(_.userId == model.slackUserId)
      if (feedback.nonEmpty) {
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.KIFIBOT_FUNNEL, SlackMessageRequest.inhouse(
          txt = DescriptionElements(
            "Slack user", model.slackUserId.value, "in", model.slackTeamId.value, "said some things to Kifi-bot"
          ),
          attachments = feedback.map(msg => SlackAttachment.simple(DescriptionElements(msg.text)))
        ))
      }
      msgs.map(_.timestamp).maxOpt.foreach { timestamp =>
        slackLog.info("Setting", model.slackUserId.value, "to have timestamp", timestamp.value)
        db.readWrite { implicit s => schedulingRepo.updateLastIngestedMessage(model.id.get, timestamp) }
      }
    }
  }
  private def getNewFeedbackMessages(model: KifibotFeedback, token: SlackBotAccessToken, limit: Int): Future[Seq[SlackHistoryMessage]] = {
    slackLog.info("Trying to grab up to", limit, "feedback messages for", model.slackUserId.value, "greater than", model.lastIngestedMessageTimestamp.toString)
    FutureHelpers.foldLeftUntil(Stream.continually(()))((Seq.empty[SlackHistoryMessage], model.lastIngestedMessageTimestamp)) {
      case ((msgs, lastTimestamp), _) =>
        slackClient.getIMHistory(token, model.kifiBotDmChannel, lastTimestamp, messagesPerRequest).map { nextMsgs =>
          val allMsgs = (msgs ++ nextMsgs).take(limit)
          ((allMsgs, nextMsgs.map(_.timestamp).maxOpt), nextMsgs.isEmpty || allMsgs.length == limit)
        }
    }.map(_._1)
  }
}
