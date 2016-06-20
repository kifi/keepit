package com.keepit.controllers.client

import java.io.{ File, FileOutputStream }
import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.google.inject.Inject
import com.keepit.commanders.KeepExportCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.EitherFormat
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.export.{ FullExportCommander, FullStreamingExport, S3KifiExportStore }
import com.keepit.model.UserValues.UserValueIntHandler
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.BasicUser
import play.api.libs.iteratee.{ Enumeratee, Enumerator, Iteratee }
import play.api.libs.json.{ JsArray, JsValue, Json }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

class KeepExportController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  keepExportCommander: KeepExportCommander,
  fullExportCommander: FullExportCommander,
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
    val fileEnum = export.spaces.flatMap { space =>
      space.libraries.flatMap { library =>
        library.keeps.flatMap { keep =>
          fullKeepPage(keep)
        } andThen fullLibraryPage(library)
      } andThen fullSpacePage(space)
    } andThen fullIndexPage(export)

    val exportBase = s"${request.user.externalId.id}-kifi-export"

    val exportFile = new File(exportBase + ".zip")
    fileEnum.run(Iteratee.fold(new ZipOutputStream(new FileOutputStream(exportFile))) {
      case (zip, (filename, value)) =>
        zip.putNextEntry(new ZipEntry(s"$exportBase/$filename.json"))
        zip.write(Json.prettyPrint(value).map(_.toByte).toArray)
        zip.closeEntry()
        zip
    }).andThen {
      case Failure(fail) =>
        slackLog.error(s"[${clock.now}] Failed while writing user ${request.userId}'s export: ${fail.getMessage}")
      case Success(zip) =>
        zip.close()
        slackLog.info(s"[${clock.now}] Done writing user ${request.userId}'s export to $exportBase.zip, uploading to S3")
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

  private def fullIndexPage(export: FullStreamingExport.Root): Enumerator[(String, JsValue)] = {
    implicit val spaceWrites = EitherFormat.keyedWrites[BasicUser, BasicOrganization]("user", "org")
    slackLog.info(s"[${clock.now}] Writing ${export.user.firstName}'s index page")
    export.spaces.map(_.space).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { spaces =>
      log.info(s"Exporting ${spaces.length} spaces")
      "index" -> Json.obj(
        "me" -> export.user,
        "spaces" -> JsArray(spaces.map { space =>
          val partialSpace = spaceWrites.writes(space)
          partialSpace
        })
      )
    })
  }
  private def fullSpacePage(space: FullStreamingExport.SpaceExport): Enumerator[(String, JsValue)] = {
    implicit val spaceWrites = EitherFormat.keyedWrites[BasicUser, BasicOrganization]("user", "org")
    val path = space.space.fold(u => "users/" + u.externalId.id, o => "orgs/" + o.orgId.id)
    space.libraries.map(_.library).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { libs =>
      val fullSpace = spaceWrites.writes(space.space)
      path -> Json.obj(
        "space" -> fullSpace,
        "libraries" -> JsArray(libs.map { lib =>
          val partialLibrary = Json.obj(
            "id" -> Library.publicId(lib.id.get),
            "name" -> lib.name,
            "description" -> lib.description
          )
          partialLibrary
        })
      )
    })
  }
  def fullLibraryPage(library: FullStreamingExport.LibraryExport): Enumerator[(String, JsValue)] = {
    val path = "libraries/" + Library.publicId(library.library.id.get).id
    val fullLibrary = Json.obj("name" -> library.library.name)
    library.keeps.map(_.keep).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { keeps =>
      path -> Json.obj(
        "library" -> fullLibrary,
        "keeps" -> JsArray(keeps.map { keep =>
          val partialKeep = Json.obj(
            "id" -> Keep.publicId(keep.id.get),
            "title" -> keep.title,
            "url" -> keep.url,
            "keptAt" -> keep.keptAt
          )
          partialKeep
        })
      )
    })
  }

  def fullKeepPage(keep: FullStreamingExport.KeepExport): Enumerator[(String, JsValue)] = {
    val path = "keeps/" + Keep.publicId(keep.keep.id.get).id
    if (keep.discussion.isEmpty) Enumerator.empty
    else Enumerator {
      path -> Json.obj(
        "id" -> Keep.publicId(keep.keep.id.get),
        "summary" -> keep.uri.flatMap(_.article.description),
        "messages" -> keep.discussion.fold(Seq.empty[String])(_.messages.map(_.text))
      )
    }
  }
}
