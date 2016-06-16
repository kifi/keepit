package com.keepit.controllers.client

import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.google.inject.Inject
import com.keepit.commanders.KeepExportCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.EitherFormat
import com.keepit.common.time._
import com.keepit.export.{ FullStreamingExport, FullExportCommander }
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.iteratee.{ Iteratee, Enumeratee, Enumerator }
import play.api.libs.json.{ JsValue, Writes, JsArray, Json }

import scala.concurrent.{ ExecutionContext, Future }

class KeepExportController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  keepExportCommander: KeepExportCommander,
  fullExportCommander: FullExportCommander,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

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

  def fullKifiExport() = UserAction { request =>
    val export = fullExportCommander.fullExport(request.userId)
    val fileEnum = export.spaces.flatMap { space =>
      space.libraries.flatMap { library =>
        library.keeps.flatMap { keep =>
          fullKeepPage(keep)
        } andThen fullLibraryPage(library)
      } andThen fullSpacePage(space)
    } andThen fullIndexPage(export)

    val exportBase = s"${request.user.primaryUsername.get.normalized.value}-kifi-export"
    val enum = Enumerator.outputStream { os =>
      val zip = new ZipOutputStream(os)
      fileEnum.run(Iteratee.foreach {
        case (filename, value) =>
          zip.putNextEntry(new ZipEntry(s"$exportBase/$filename.json"))
          zip.write(Json.prettyPrint(value).map(_.toByte).toArray)
          zip.closeEntry()
      }).andThen {
        case res => zip.close()
      }
    }
    Ok.chunked(enum andThen Enumerator.eof).withHeaders(
      "Content-Type" -> "application/zip",
      "Content-Disposition" -> s"attachment; filename=$exportBase.zip"
    )
  }

  private def fullIndexPage(export: FullStreamingExport.Root): Enumerator[(String, JsValue)] = {
    implicit val spaceWrites = EitherFormat.keyedWrites[BasicUser, BasicOrganization]("user", "org")
    export.spaces.map(_.space).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { spaces =>
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
    space.libraries.map(_.library).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { libs =>
      val fullSpace = spaceWrites.writes(space.space)
      space.space.fold(_.externalId.id, _.orgId.id) -> Json.obj(
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
    val fullLibrary = Json.obj("name" -> library.library.name)
    library.keeps.map(_.keep).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { keeps =>
      Library.publicId(library.library.id.get).id -> Json.obj(
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

  def fullKeepPage(keep: FullStreamingExport.KeepExport): Enumerator[(String, JsValue)] = Enumerator.empty
}
