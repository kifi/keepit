package com.keepit.controllers.client

import java.io.{ File, FileOutputStream }
import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.google.inject.Inject
import com.keepit.commanders.KeepExportCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.export.{ FullExportProducer, FullExportFormatter, S3KifiExportStore }
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
  fullExportCommander: FullExportProducer,
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
}
