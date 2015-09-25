package com.keepit.model

import com.keepit.common.db.Id
import org.joda.time.DateTime
import play.api.libs.json._

case class KeepExport(
  createdAt: DateTime,
  title: Option[String] = None,
  url: String,
  tags: Option[String] = None)

sealed abstract class KeepExportRequest
case class OrganizationKeepExportRequest(userId: Id[User], orgIds: Set[Id[Organization]]) extends KeepExportRequest
case class PersonalKeepExportRequest(userId: Id[User]) extends KeepExportRequest

sealed abstract class KeepExportFormat(val value: String)
object KeepExportFormat {
  case object HTML extends KeepExportFormat("html")
  case object JSON extends KeepExportFormat("json")

  implicit val format: Format[KeepExportFormat] =
    Format(__.read[String].map(KeepExportFormat(_)), new Writes[KeepExportFormat] {
      def writes(kef: KeepExportFormat) = JsString(kef.value)
    })

  def apply(str: String): KeepExportFormat = {
    str match {
      case HTML.value => HTML
      case JSON.value => JSON
    }
  }
}

case class KeepExportResponse(keeps: Seq[Keep], keepTags: Map[Id[Keep], Seq[String]]) {
  def formatAsHtml: String = {
    val before = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
                   |<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
                   |<!--This is an automatically generated file.
                   |It will be read and overwritten.
                   |Do Not Edit! -->
                   |<Title>Kifi Bookmarks Export</Title>
                   |<H1>Bookmarks</H1>
                   |<DL>
                   |""".stripMargin
    val after = "\n</DL>"

    def createExport(keep: Keep): String = {
      // Parse Tags
      val title = keep.title.map(_.replace("&", "&amp;")) getOrElse ""
      val tagString = {
        val tags = keepTags(keep.id.get).map(_.replace("&", "&amp;").replace("\"", ""))
        s""""${tags.mkString(",")}""""
      }
      val date = keep.keptAt.getMillis / 1000
      val line = {
        s"""<DT><A HREF="${keep.url}" ADD_DATE="$date" TAGS=$tagString>$title</A>"""
      }
      line
    }
    before + keeps.map(createExport).mkString("\n") + after
  }
  def formatAsJson: JsValue = {
    val keepJsonArray = keeps.map { keep =>
      Json.obj(
        "title" -> (keep.title.getOrElse(""): String),
        "date" -> keep.keptAt.getMillis / 1000,
        "url" -> keep.url,
        "source" -> keep.source.value,
        "note" -> keep.note.getOrElse[String](""),
        "tags" -> keepTags(keep.id.get)
      )
    }
    Json.obj("keeps" -> JsArray(keepJsonArray))
  }
}
