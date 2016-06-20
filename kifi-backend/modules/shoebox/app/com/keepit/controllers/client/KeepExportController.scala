package com.keepit.controllers.client

import java.io.{ File, FileOutputStream }
import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.google.inject.Inject
import com.keepit.commanders.KeepExportCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.export.{ FullExportCommander, FullExportFormatter, S3KifiExportStore }
import com.keepit.model.UserValues.UserValueIntHandler
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import play.api.libs.iteratee.{ Enumerator, Iteratee }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

class KeepExportController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  keepExportCommander: KeepExportCommander,
  fullExportCommander: FullExportCommander,
  formatter: FullExportFormatter,
  userValueRepo: UserValueRepo,
  clock: Clock,
  exportStore: S3KifiExportStore,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val inhouseSlackClient: InhouseSlackClient)
    extends UserActions with ShoeboxServiceController {
  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)

  def exportOrganizationKeeps() = UserAction.async(parse.anyContent) { request =>
    request.body.asFormUrlEncoded.flatMap { form =>
      for {
        format <- form.get("format").flatMap(_.headOption.map(KeepExportFormat.apply))
        orgIds <- form.get("orgIds").map(_.map(PublicId.apply[Organization]))
      } yield (format, orgIds.toSet)
    }.orElse {
      request.body.asJson.flatMap { json =>
        for {
          format <- (json \ "format").asOpt[KeepExportFormat]
          orgIds <- (json \ "orgIds").asOpt[Set[PublicId[Organization]]]
        } yield (format, orgIds)
      }
    }.map {
      case ((format, pubIds)) =>
        val orgIds = pubIds.map { pubId => Organization.decodePublicId(pubId).get }
        keepExportCommander.exportKeeps(OrganizationKeepExportRequest(request.userId, orgIds)).map { response =>
          format match {
            case KeepExportFormat.JSON => Ok(response.get.formatAsJson).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.json\"")
            case KeepExportFormat.HTML => Ok(response.get.formatAsHtml).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.html\"")
          }
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def exportPersonalKeeps() = UserAction.async(parse.anyContent) { request =>
    request.body.asFormUrlEncoded.flatMap { form =>
      form.get("format").flatMap(_.headOption.map(KeepExportFormat.apply))
    }.orElse {
      request.body.asJson.flatMap { json =>
        (json \ "format").asOpt[KeepExportFormat]
      }
    }.map {
      case (format) =>
        keepExportCommander.exportKeeps(PersonalKeepExportRequest(request.userId)).map { response =>
          format match {
            case KeepExportFormat.JSON => Ok(response.get.formatAsJson).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.json\"")
            case KeepExportFormat.HTML => Ok(response.get.formatAsHtml).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.html\"")
          }
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def downloadFullExport() = UserAction.async { request =>
    db.readOnlyMaster { implicit s =>
      userValueRepo.getUserValue(request.userId, UserValueName.FULL_EXPORT_LOCATION).map(_.value)
    }.fold {
      Future.successful(BadRequest(Json.obj(
        "error" -> "no_export_available",
        "hint" -> "create the export and wait a bit for it to finish"
      )))
    } { key =>
      db.readWrite { implicit s =>
        val prevCount = userValueRepo.getValue(request.userId, UserValueIntHandler(UserValueName.FULL_EXPORT_DOWNLOAD_COUNT, 0))
        userValueRepo.setValue(request.userId, UserValueName.FULL_EXPORT_DOWNLOAD_COUNT, prevCount + 1)
      }
      exportStore.retrieve(key).map { file =>
        Ok.chunked(Enumerator.fromFile(file.file))
      }
    }
  }
  def createFullExport() = UserAction(parse.tolerantJson) { request =>
    val export = fullExportCommander.fullExport(request.userId)

    slackLog.info(s"[${clock.now}] Formatting user ${request.userId}'s export as JSON")
    val fileEnum = formatter.json(export)

    val exportBase = s"${request.user.externalId.id}-kifi-export"
    val exportFile = new File(exportBase + ".zip")
    val init = (Set.empty[String], new ZipOutputStream(new FileOutputStream(exportFile)))
    fileEnum.run(Iteratee.fold(init) {
      case ((existingEntries, zip), (path, contents)) =>
        if (!existingEntries.contains(path)) {
          zip.putNextEntry(new ZipEntry(s"$exportBase/$path.json"))
          zip.write(contents.map(_.toByte).toArray)
          zip.closeEntry()
        }
        (existingEntries + path, zip)
    }).andThen {
      case Failure(fail) =>
        slackLog.error(s"[${clock.now}] Failed while writing user ${request.userId}'s export: ${fail.getMessage}")
      case Success((entries, zip)) =>
        zip.close()
        slackLog.info(s"[${clock.now}] Done writing user ${request.userId}'s export to $exportFile (${entries.size} entries), uploading to S3")
        exportStore.store(exportFile).andThen {
          case Success(yay) =>
            slackLog.info(s"[${clock.now}] Uploaded $exportBase.zip, key = ${yay.getKey}")
            db.readWrite { implicit s =>
              userValueRepo.setValue(request.userId, UserValueName.FULL_EXPORT_LOCATION, yay.getKey)
            }
          case Failure(aww) =>
            slackLog.error(s"[${clock.now}] Could not upload $exportBase.zip because ${aww.getMessage}")
            airbrake.notify(aww)
        }
    }
    Ok(Json.obj("status" -> "started"))
  }
}
