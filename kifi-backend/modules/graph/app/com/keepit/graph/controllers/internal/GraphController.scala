package com.keepit.graph.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.GraphServiceController
import com.keepit.common.logging.Logging
import com.keepit.graph.manager.{GraphUpdaterState, GraphManager}
import play.api.mvc.Action
import play.api.libs.json._
import play.api.libs.json.JsString
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber

class GraphController @Inject() (
  graphManager: GraphManager
) extends GraphServiceController with Logging {

  def discover() = Action { request =>
    Ok
  }

  def getGraphStatistics() = Action { request =>

    val statistics = graphManager.statistics

    val writableVertexStatistics = JsArray(statistics.vertexStatistics.flatMap { case (vertexKind, count) =>
      Seq(JsString(vertexKind.toString), JsNumber(count))
    }.toSeq)

    val writableEdgeStatistics = JsArray(statistics.edgeStatistics.flatMap { case ((sourceKind, destinationKind, edgeKind), count) =>
      Seq(JsString(sourceKind.toString), JsString(destinationKind.toString), JsString(edgeKind.toString), JsNumber(count))
    }.toSeq)

    val json = Json.obj(
      "vertices" -> writableVertexStatistics,
      "edges" -> writableEdgeStatistics
    )

    Ok(json)
  }

  def getGraphUpdaterState() = Action { request =>
    val state = graphManager.state
    val json = JsObject(state.state.map { case (kind, seq) => kind.toString -> JsNumber(seq) }.toSeq)
    Ok(json)
  }
}
