package com.keepit.export

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.json.EitherFormat
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.iteratee._
import play.api.libs.json._

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[FullExportCommanderImpl])
trait FullExportFormatter {
  type FilePath = String
  type FileContents = String

  def json(export: FullStreamingExport.Root): Enumerator[(FilePath, FileContents)]
}

class FullExportFormatterImpl @Inject() (
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends FullExportFormatter with Logging {

  def json(export: FullStreamingExport.Root): Enumerator[(FilePath, FileContents)] = {
    val enum = export.spaces.flatMap { space =>
      space.libraries.flatMap { library =>
        library.keeps.flatMap { keep =>
          fullKeepPage(keep)
        } andThen fullLibraryPage(library)
      } andThen fullSpacePage(space)
    } andThen fullIndexPage(export)
    enum.map {
      case (path, contents) => path -> Json.prettyPrint(contents)
    }
  }

  private def fullIndexPage(export: FullStreamingExport.Root): Enumerator[(String, JsValue)] = {
    implicit val spaceWrites = EitherFormat.keyedWrites[BasicUser, BasicOrganization]("user", "org")
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
