package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.time._
import com.keepit.model.NormalizedURI
import com.keepit.search.Lang
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.common.analytics.reports._

class CompleteReportSerializer extends Format[CompleteReport] {
  def writes(report: CompleteReport): JsValue =
    JsObject(List(
        "reportName" -> JsString(report.reportName),
        "reportVersion" -> JsString(report.reportVersion),
        "createdAt" -> JsString(report.createdAt.toStandardTimeString),
        "report" -> JsObject(report.list map (r => r.date.toStandardTimeString -> JsObject((r.fields map (s => s._1 -> JsString(s._2))).toSeq)))
      )
    )

  def reads(json: JsValue): CompleteReport =  {
    val list = (json \ "report").as[JsObject].fields.map { s =>
      val date = parseStandardTime(s._1)
      val fields = (s._2.as[JsObject].fields map { t =>
        t._1 -> t._2.as[String]
      }).toMap
      ReportRow(date, fields)
    }
    CompleteReport(
      reportName = (json \ "reportName").as[String],
      reportVersion = (json \ "reportVersion").as[String],
      createdAt = parseStandardTime((json \ "createdAt").as[String]),
      list = list
    )
  }
}

object CompleteReportSerializer {
  implicit val completeReportSerializer = new CompleteReportSerializer
}
