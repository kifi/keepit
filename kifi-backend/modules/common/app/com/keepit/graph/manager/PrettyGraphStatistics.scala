package com.keepit.graph.manager

import play.api.libs.json._
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import play.api.libs.json.JsString

case class PrettyGraphStatistics(vertexStatistics: Map[String, Long], edgeStatistics: Map[(String, String, String), Long])

object PrettyGraphStatistics {

  implicit val format = new Format[PrettyGraphStatistics] {

    def reads(json: JsValue): JsResult[PrettyGraphStatistics] = for {

      vertexStatistics <- (json \ "vertexStatistics").validate[JsArray].map(_.value.sliding(2,2).map {
        case Seq(JsString(vertexKind), JsNumber(count)) => (vertexKind -> count.toLong)
      }.toMap)

      edgeStatistics <- (json \ "edgeStatistics").validate[JsArray].map(_.value.sliding(4,4).map {
        case Seq(JsString(sourceKind), JsString(destinationKind), JsString(edgeKind), JsNumber(count)) => ((sourceKind, destinationKind, edgeKind) -> count.toLong)
      }.toMap)

    } yield PrettyGraphStatistics(vertexStatistics, edgeStatistics)


    def writes(statistics: PrettyGraphStatistics): JsValue = {

      val vertexStatistics = JsArray(statistics.vertexStatistics.flatMap { case (vertexKind, count) =>
        Seq(JsString(vertexKind), JsNumber(count))
      }.toSeq)

      val edgeStatistics = JsArray(statistics.edgeStatistics.flatMap { case ((sourceKind, destinationKind, edgeKind), count) =>
        Seq(JsString(sourceKind), JsString(destinationKind), JsString(edgeKind), JsNumber(count))
      }.toSeq)

      Json.obj(
        "vertexStatistics" -> vertexStatistics,
        "edgeStatistics" -> edgeStatistics
      )
    }
  }
}

case class PrettyGraphState(state: Map[String, Long])

object PrettyGraphState {
  implicit val format = Json.format[PrettyGraphState]
}

