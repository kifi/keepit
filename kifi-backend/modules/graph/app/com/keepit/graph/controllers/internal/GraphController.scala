package com.keepit.graph.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.GraphServiceController
import com.keepit.common.logging.Logging
import com.keepit.graph.manager.{GraphUpdaterState, GraphStatistics, GraphManager}
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.graph.wander.{Wanderlust, WanderingCommander}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class GraphController @Inject() (
  graphManager: GraphManager,
  wanderingCommander: WanderingCommander
) extends GraphServiceController with Logging {

  def wander() = SafeAsyncAction(parse.json) { request =>
    val wanderlust = request.body.as[Wanderlust]
    val collisions = wanderingCommander.wander(wanderlust)
    val json = Json.toJson(collisions)
    Ok(json)
  }

  def getGraphStatistics() = Action { request =>
    val statistics = graphManager.statistics
    val json = Json.toJson(GraphStatistics.prettify(statistics))
    Ok(json)
  }

  def getGraphUpdaterState() = Action { request =>
    val state = graphManager.state
    val json = Json.toJson(GraphUpdaterState.prettify(state))
    Ok(json)
  }
}
