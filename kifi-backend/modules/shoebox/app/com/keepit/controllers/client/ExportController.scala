package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.controller.{ NonUserRequest, UserRequest, ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.export._
import com.keepit.model.UserValues.UserValueIntHandler
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Success, Failure }

class ExportController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  requestRepo: FullExportRequestRepo,
  exportScheduler: FullExportScheduler,
  exportStore: S3KifiExportStore,
  exportActor: ActorInstance[FullExportProcessingActor],
  userValueRepo: UserValueRepo,
  userExperimentCommander: LocalUserExperimentCommander,
  userEmailAddressRepo: UserEmailAddressRepo,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val inhouseSlackClient: InhouseSlackClient)
    extends UserActions with ShoeboxServiceController {
  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)

  def addEmailToNotify() = UserAction(parse.tolerantJson) { request =>
    val emailStr = (request.body \ "email").as[String]
    EmailAddress.validate(emailStr) match {
      case Failure(_) => BadRequest(Json.obj("error" -> "invalid_email"))
      case Success(emailAddress) =>
        val wasUpdated = db.readWrite(implicit s => requestRepo.updateNotifyEmail(request.userId, emailAddress))
        if (wasUpdated) Ok(Json.obj("status" -> "success"))
        else NotFound
    }
  }

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
    val emailOpt = (request.body \ "email").asOpt[String].flatMap(EmailAddress.validate(_).toOption)
    val req = db.readWrite(implicit s => exportScheduler.internExportRequest(request.userId, emailOpt))
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

  def getExportPage() = MaybeUserAction { request =>
    request match {
      case nur: NonUserRequest[_] => Redirect(com.keepit.controllers.website.routes.HomeController.home())
      case ur: UserRequest[_] =>
        val userId = ur.userId

        val (export, systemState, userEmail) = db.readOnlyMaster { implicit s =>
          val export = exportScheduler.getExportRequest(userId)
          val systemState = userExperimentCommander.getBuzzState(Some(userId))
          val userEmail = requestRepo.getByUser(userId).flatMap(_.notifyEmail) orElse Try(userEmailAddressRepo.getByUser(userId)).toOption
          (export, systemState, userEmail)
        }

        val allowReexport = export.exists(ep => FullExportSchedulerConfig.oldEnoughToBeReprocessed(ep, currentDateTime))

        Ok(views.html.website.export(export.map(_.status), systemState, userEmail, allowReexport))
    }
  }
}
