package com.keepit.export

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.json.EitherFormat
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.iteratee._
import play.api.libs.json._
import com.keepit.common.strings.StringWithReplacements
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext

object FullExportFormatter {
  val beforeHtml = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
                 |<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
                 |<!--This is an automatically generated file.
                 |It will be read and overwritten.
                 |Do Not Edit! -->
                 |<Title>Kifi Bookmarks Export</Title>
                 |<H1>Bookmarks</H1>""".stripMargin
}

@ImplementedBy(classOf[FullExportFormatterImpl])
trait FullExportFormatter {
  type EntityId = String
  type EntityContents = JsValue
  type HtmlLine = String

  def assignments(export: FullStreamingExport.Root): Enumerator[(EntityId, EntityContents)]
  def bookmarks(export: FullStreamingExport.Root): Enumerator[HtmlLine]
}

class FullExportFormatterImpl @Inject() (
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends FullExportFormatter {

  def assignments(export: FullStreamingExport.Root): Enumerator[(String, JsValue)] = {
    val init: Enumerator[(String, JsValue)] = Enumerator(
      "users" -> Json.obj(),
      "orgs" -> Json.obj(),
      "libraries" -> Json.obj(),
      "keeps" -> Json.obj()
    )
    init andThen indexBlob(export) andThen export.spaces.flatMap { space =>
      spaceBlob(space) andThen space.libraries.flatMap { library =>
        libraryBlob(library) andThen library.keeps.flatMap { keep =>
          keepBlob(keep)
        }
      }
    } andThen export.looseKeeps.flatMap(keepBlob)
  }

  private def indexBlob(export: FullStreamingExport.Root): Enumerator[(String, JsValue)] = {
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
  private def spaceBlob(space: FullStreamingExport.SpaceExport): Enumerator[(String, JsValue)] = {
    implicit val spaceWrites: OWrites[Either[BasicUser, BasicOrganization]] = OWrites {
      case Left(u) => BasicUser.format.writes(u)
      case Right(o) => BasicOrganization.defaultFormat.writes(o)
    }
    val entity = space.space.fold(
      u => s"""users["${u.externalId.id}"]""",
      o => s"""orgs["${o.orgId.id}"]"""
    )
    space.libraries.map(_.library.id.get).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { libIds =>
      val fullSpace = spaceWrites.writes(space.space)
      entity -> (fullSpace ++ Json.obj("libraries" -> libIds.map(Library.publicId)))
    })
  }
  private def libraryBlob(library: FullStreamingExport.LibraryExport): Enumerator[(String, JsValue)] = {
    val entity = s"""libraries["${Library.publicId(library.library.id.get).id}"]"""
    val fullLibrary = Json.obj(
      "id" -> Library.publicId(library.library.id.get),
      "name" -> library.library.name,
      "description" -> library.library.description,
      "numKeeps" -> library.library.keepCount
    )
    library.keeps.map(_.keep.id.get).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { keepIds =>
      entity -> (fullLibrary ++ Json.obj("keeps" -> keepIds.map(Keep.publicId)))
    })
  }

  private def keepBlob(keep: FullStreamingExport.KeepExport): Enumerator[(String, JsValue)] = {
    val entity = s"""keeps["${Keep.publicId(keep.keep.id.get).id}"]"""
    Enumerator {
      entity -> Json.obj(
        "id" -> Keep.publicId(keep.keep.id.get),
        "keptAt" -> keep.keep.keptAt,
        "lastActivityAt" -> keep.keep.lastActivityAt,
        "title" -> keep.keep.title,
        "url" -> keep.keep.url,
        "note" -> keep.keep.note,
        "tags" -> keep.tags,
        "libraries" -> keep.keep.recipients.libraries.toSeq.sorted.map(Library.publicId),
        "summary" -> keep.uri.flatMap(_.article.description),
        "messages" -> keep.messages
      )
    }
  }

  def bookmarks(export: FullStreamingExport.Root): Enumerator[HtmlLine] = {
    Enumerator(FullExportFormatter.beforeHtml) andThen bookmarkFolder("Kifi") {
      bookmarkFolder("Private Discussions") {
        bookmarkPages(export.looseKeeps)
      } andThen export.spaces.flatMap { space =>
        bookmarkFolder(space.space.fold(_.fullName, _.name)) {
          space.libraries.flatMap { library =>
            bookmarkFolder(library.library.name, Some(library.library.createdAt)) {
              bookmarkPages(library.keeps)
            }
          }
        }
      }
    }
  }

  private def bookmarkFolder(name: String, createdAt: Option[DateTime] = None)(content: Enumerator[HtmlLine]): Enumerator[HtmlLine] = {
    val header = s"""<DT><H3${createdAt.map(t => s""" FOLDED ADD_DATE="${t.getMillis / 1000}"""") getOrElse ""}>$name</H3>"""
    Enumerator(header, "<DL><p>") andThen content andThen Enumerator("</DL><p>")
  }

  private def bookmarkPages(keeps: Enumerator[FullStreamingExport.KeepExport]): Enumerator[HtmlLine] = {
    keeps.map { keepExport =>
      val keep = keepExport.keep
      val title = keep.title.map(_.replace("&", "&amp;")) getOrElse ""
      val date = keep.keptAt.getMillis / 1000
      val tagString = {
        def sanitize(tag: String): String = tag.replaceAllLiterally("&" -> "&amp;", "\"" -> "")
        val tags = keepExport.tags.map(tag => sanitize(tag.tag))
        s""""${(tags).mkString(",")}""""
      }
      s"""<DT><a href="${keep.url}" add_date="$date" tags=$tagString>$title</a>"""
    }
  }
}
