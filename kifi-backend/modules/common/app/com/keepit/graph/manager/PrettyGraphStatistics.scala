package com.keepit.graph.manager

import play.api.libs.json._
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import play.api.libs.json.JsString

case class PrettyGraphStatistics(vertexStatistics: Map[String, (Long, Double, Double)], edgeStatistics: Map[(String, String, String), (Long, Double, Double)])

object PrettyGraphStatistics {

  implicit val format = new Format[PrettyGraphStatistics] {

    def reads(json: JsValue): JsResult[PrettyGraphStatistics] = for {

      vertexStatistics <- (json \ "vertexStatistics").validate[JsArray].map(_.value.sliding(4,4).map {
        case Seq(JsString(vertexKind), JsNumber(count), JsNumber(outgoingDegree), JsNumber(incomingDegree)) =>
          (vertexKind -> (count.toLong, outgoingDegree.toDouble, incomingDegree.toDouble))
      }.toMap)

      edgeStatistics <- (json \ "edgeStatistics").validate[JsArray].map(_.value.sliding(6,6).map {
        case Seq(JsString(sourceKind), JsString(destinationKind), JsString(edgeKind), JsNumber(count), JsNumber(outgoingDegree), JsNumber(incomingDegree)) =>
          ((sourceKind, destinationKind, edgeKind) -> (count.toLong, outgoingDegree.toDouble, incomingDegree.toDouble))
      }.toMap)

    } yield PrettyGraphStatistics(vertexStatistics, edgeStatistics)


    def writes(statistics: PrettyGraphStatistics): JsValue = {

      val vertexStatistics = JsArray(statistics.vertexStatistics.flatMap { case (vertexKind, (count, outgoingDegree, incomingDegree)) =>
        Seq(JsString(vertexKind), JsNumber(count), JsNumber(outgoingDegree), JsNumber(incomingDegree))
      }.toSeq)

      val edgeStatistics = JsArray(statistics.edgeStatistics.flatMap { case ((sourceKind, destinationKind, edgeKind), (count, outgoingDegree, incomingDegree)) =>
        Seq(JsString(sourceKind), JsString(destinationKind), JsString(edgeKind), JsNumber(count), JsNumber(outgoingDegree), JsNumber(incomingDegree))
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

