package com.keepit.graph.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.GraphServiceController
import com.keepit.common.logging.Logging
import com.keepit.graph.manager.{GraphStatistics, GraphManager}
import play.api.mvc.Action
import play.api.libs.json._

class GraphController @Inject() (
  graphManager: GraphManager
) extends GraphServiceController with Logging {

  def discover() = Action { request =>
    Ok
  }

  def getGraphStatistics() = Action { request =>
    val statistics = graphManager.statistics
    val json = Json.toJson(GraphStatistics.prettify(statistics))
    Ok(json)
  }

  def getGraphUpdaterState() = Action { request =>
    val state = graphManager.state
    val json = Json.toJson(state)
    Ok(json)
  }
}
