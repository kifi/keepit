package com.keepit.graph.manager

import play.api.libs.json._
import play.api.libs.json.JsArray
import play.api.libs.json.JsString

case class PrettyGraphStatistics(vertexStatistics: Map[String, (String, String, String)], edgeStatistics: Map[(String, String, String), (String, String, String)])

object PrettyGraphStatistics {

  implicit val format = new Format[PrettyGraphStatistics] {

    def reads(json: JsValue): JsResult[PrettyGraphStatistics] = for {

      vertexStatistics <- (json \ "vertexStatistics").validate[Seq[String]].map(_.sliding(4, 4).map {
        case Seq(vertexKind, count, outgoingDegree, incomingDegree) =>
          (vertexKind -> (count, outgoingDegree, incomingDegree))
      }.toMap)

      edgeStatistics <- (json \ "edgeStatistics").validate[Seq[String]].map(_.sliding(6, 6).map {
        case Seq(sourceKind, destinationKind, edgeKind, count, outgoingDegree, incomingDegree) =>
          ((sourceKind, destinationKind, edgeKind) -> (count, outgoingDegree, incomingDegree))
      }.toMap)

    } yield PrettyGraphStatistics(vertexStatistics, edgeStatistics)

    def writes(statistics: PrettyGraphStatistics): JsValue = {

      val vertexStatistics = JsArray(statistics.vertexStatistics.flatMap {
        case (vertexKind, (count, outgoingDegree, incomingDegree)) =>
          Seq(vertexKind, count, outgoingDegree, incomingDegree).map(JsString)
      }.toSeq)

      val edgeStatistics = JsArray(statistics.edgeStatistics.flatMap {
        case ((sourceKind, destinationKind, edgeKind), (count, outgoingDegree, incomingDegree)) =>
          Seq(sourceKind, destinationKind, edgeKind, count, outgoingDegree, incomingDegree).map(JsString)
      }.toSeq)

      Json.obj(
        "vertexStatistics" -> vertexStatistics,
        "edgeStatistics" -> edgeStatistics
      )
    }
  }
}

case class PrettyGraphState(state: Map[String, String])

object PrettyGraphState {
  implicit val format = Json.format[PrettyGraphState]
}

