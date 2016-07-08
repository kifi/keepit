package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.export.FullExportFormatter
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

case class KeepExportResponse(keeps: Seq[Keep], keepTags: Map[Id[Keep], Seq[String]], keepLibs: Map[Id[Keep], Seq[Library]]) {
  def formatAsHtml: String = {
    val before = FullExportFormatter.beforeHtml + "\n<DL>"
    val after = "</DL\n>" + FullExportFormatter.afterHtml

    def createExport(keep: Keep): String = {
      // Parse Tags
      val title = keep.title.map(_.replace("&", "&amp;")) getOrElse ""
      val tagString = {
        val tags = keepTags(keep.id.get).map(_.replace("&", "&amp;").replace("\"", ""))
        val libNames = keepLibs(keep.id.get).map(_.name.replace("&", "&amp;").replace("\"", ""))
        s""""${(tags ++ libNames).mkString(",")}""""
      }
      val date = keep.keptAt.getMillis / 1000
      val line = {
        s"""<DT><A HREF="${keep.url}" ADD_DATE="$date" TAGS=$tagString>$title</A>"""
      }
      line
    }
    before + (keeps.map(createExport) :+ after).mkString("\n")
  }
  def formatAsJson: JsValue = {
    val keepJsonArray = keeps.map { keep =>
      Json.obj(
        "title" -> (keep.title.getOrElse(""): String),
        "date" -> keep.keptAt.getMillis / 1000,
        "url" -> keep.url,
        "source" -> keep.source.value,
        "note" -> keep.note.getOrElse[String](""),
        "tags" -> keepTags(keep.id.get),
        "libraries" -> keepLibs(keep.id.get).map(_.name)
      )
    }
    Json.obj("keeps" -> JsArray(keepJsonArray))
  }
}
