package com.keepit.eliza.controllers.internal

import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.logging.Logging
import com.keepit.model.{ User }
import com.keepit.common.db.{ Id }

import scala.concurrent.future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.mvc.Action
import play.api.libs.json.{ Json, JsObject, JsArray }

import com.google.inject.Inject
import com.keepit.eliza.commanders.{ MessagingCommander, NotificationJson, NotificationCommander, ElizaStatsCommander }
import com.keepit.eliza.model.UserThreadStats

class ElizaController @Inject() (
    notificationRouter: WebSocketRouter,
    elizaStatsCommander: ElizaStatsCommander) extends ElizaServiceController with Logging {

  def getUserThreadStats(userId: Id[User]) = Action { request =>
    Ok(UserThreadStats.format.writes(elizaStatsCommander.getUserThreadStats(userId)))
  }

  def sendToUserNoBroadcast() = Action.async { request =>
    future {
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUserNoBroadcast(userId, data)
      Ok("")
    }
  }

  def sendToUser() = Action.async { request =>
    future {
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUser(userId, data)
      Ok("")
    }
  }

  def sendToAllUsers() = Action.async { request =>
    future {
      val req = request.body.asJson.get.asInstanceOf[JsArray]
      notificationRouter.sendToAllUsers(req)
      Ok("")
    }
  }

  def connectedClientCount() = Action { request =>
    Ok(notificationRouter.connectedSockets.toString)
  }

  def getRenormalizationSequenceNumber() = Action { _ =>
    val seqNumber = elizaStatsCommander.getCurrentRenormalizationSequenceNumber
    Ok(Json.toJson(seqNumber))
  }
}
