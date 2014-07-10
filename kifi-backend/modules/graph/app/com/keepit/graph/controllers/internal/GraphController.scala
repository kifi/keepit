package com.keepit.graph.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.GraphServiceController
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.graph.commanders.GraphCommander
import com.keepit.graph.manager.{NormalizedUriGraphUpdate, GraphUpdaterState, GraphStatistics, GraphManager}
import com.keepit.model.{SocialUserInfo, NormalizedURI, User}
import play.api.mvc.{BodyParsers, Action}
import play.api.libs.json._
import com.keepit.graph.wander.{Wanderlust, WanderingCommander}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.graph.model._
import play.api.mvc.BodyParsers.parse

class GraphController @Inject() (
  graphManager: GraphManager,
  wanderingCommander: WanderingCommander,
  graphCommander: GraphCommander
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

  def getGraphKinds() = Action { request =>
    val graphKinds = GraphKinds(VertexKind.all.map(_.code), EdgeKind.all.map(_.code))
    val json = Json.toJson(graphKinds)
    Ok(json)
  }

  def getListOfUriAndScorePairs(userId:Id[User]) = Action { request =>
    val urisList = graphCommander.getListOfUriAndScorePairs(userId)
    val json = Json.toJson(urisList)
    Ok(json)
  }

  def getListOfUserAndScorePairs(userId:Id[User]) = Action { request =>
    val usersList = graphCommander.getListOfUserAndScorePairs(userId)
    val json = Json.toJson(usersList)
    Ok(json)
  }
}
