package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.time._
import com.keepit.model.NormalizedURI
import com.keepit.search.Lang
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.common.analytics.reports._
import org.joda.time.DateTimeZone

class CompleteReportSerializer extends Format[Report] {
  def writes(report: Report): JsValue =
    JsObject(List(
        "reportName" -> JsString(report.reportName),
        "reportVersion" -> JsString(report.reportVersion),
        "createdAt" -> Json.toJson(report.createdAt),
        "report" -> JsObject(report.list map (r => UTC_DATETIME_FORMAT.print(r.date.withZone(DateTimeZone.UTC)) -> JsObject((r.fields map (s => s._1 -> JsArray(Seq(JsString(s._2.value), JsNumber(s._2.ordering))))).toSeq)))
      )
    )

  def reads(json: JsValue): JsResult[Report] =  {
    val list = (json \ "report").as[JsObject].fields.map { case (dateVal, row) =>
      val date = UTC_DATETIME_FORMAT.parseDateTime(dateVal)
      val fields = (row.as[JsObject].fields map { case (key, value) =>
        key -> value.asOpt[String].map(ValueOrdering(_, 0)).getOrElse {
          val valueOrdering = value.as[List[JsValue]]
          ValueOrdering(valueOrdering(0).as[String], valueOrdering(1).as[Int])
        }
      }).toMap
      ReportRow(date, fields)
    }
    JsSuccess(Report(
      reportName = (json \ "reportName").as[String],
      reportVersion = (json \ "reportVersion").as[String],
      createdAt = UTC_DATETIME_FORMAT.parseDateTime((json \ "createdAt").as[String]),
      list = list
    ))
  }
}

object CompleteReportSerializer {
  implicit val completeReportSerializer = new CompleteReportSerializer
}
