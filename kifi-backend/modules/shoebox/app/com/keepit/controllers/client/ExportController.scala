package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.export._
import com.keepit.model.UserValues.UserValueIntHandler
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class ExportController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  exportScheduler: FullExportScheduler,
  exportStore: S3KifiExportStore,
  exportActor: ActorInstance[FullExportProcessingActor],
  userValueRepo: UserValueRepo,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val inhouseSlackClient: InhouseSlackClient)
    extends UserActions with ShoeboxServiceController {
  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)

  def downloadFullExport() = UserAction.async { request =>
    val status = db.readOnlyMaster { implicit s =>
      exportScheduler.getExportRequest(request.userId)
    }.map(_.status)

    status match {
      case None =>
        Future.successful(BadRequest(Json.obj(
          "error" -> "no_export_available",
          "hint" -> "create the export and wait a bit for it to finish"
        )))
      case Some(FullExportStatus.NotStarted | FullExportStatus.InProgress(_)) =>
        Future.successful(BadRequest(Json.obj(
          "error" -> "export_not_ready",
          "hint" -> "export is not yet ready, please wait"
        )))
      case Some(FullExportStatus.Failed(_, _, _)) =>
        Future.successful(BadRequest(Json.obj(
          "error" -> "export_failed",
          "hint" -> "we encountered an error when creating the export, automatically retrying"
        )))
      case Some(FullExportStatus.Finished(_, _, location)) =>
        db.readWrite { implicit s =>
          val prevCount = userValueRepo.getValue(request.userId, UserValueIntHandler(UserValueName.FULL_EXPORT_DOWNLOAD_COUNT, 0))
          userValueRepo.setValue(request.userId, UserValueName.FULL_EXPORT_DOWNLOAD_COUNT, prevCount + 1)
        }
        exportStore.retrieve(location).map { file =>
          Ok.chunked(Enumerator.fromFile(file.file)).withHeaders(
            "Content-Type" -> "application/zip",
            "Content-Disposition" -> "attachment; filename=\"kifi_export.zip\""
          )
        }
    }
  }

  def requestFullExport() = UserAction(parse.tolerantJson) { request =>
    val req = db.readWrite { implicit s =>
      exportScheduler.internExportRequest(request.userId)
    }
    exportActor.ref ! IfYouCouldJustGoAhead
    req.status match {
      case FullExportStatus.NotStarted =>
        Ok(Json.obj("status" -> "queued"))
      case FullExportStatus.InProgress(startedAt) =>
        Ok(Json.obj("status" -> "in_progress", "started" -> startedAt))
      case FullExportStatus.Failed(_, failedAt, _) =>
        Ok(Json.obj("status" -> "failed", "failed" -> failedAt, "note" -> "automatically retrying"))
      case FullExportStatus.Finished(startedAt, finishedAt, _) =>
        Ok(Json.obj("status" -> "finished", "finished" -> finishedAt))
    }
  }
}
