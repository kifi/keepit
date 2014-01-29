package com.keepit.eliza.controllers.internal

import com.keepit.eliza.controllers.NotificationRouter
import com.keepit.eliza._
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.logging.Logging
import com.keepit.model.{User}
import com.keepit.common.db.{Id}

import scala.concurrent.future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.mvc.Action
import play.api.libs.json.{JsObject, JsArray}

import com.google.inject.Inject


class ElizaController @Inject() (
  notificationRouter: NotificationRouter)
    extends ElizaServiceController with Logging {

  def sendToUserNoBroadcast() = Action.async { request =>
    future{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUserNoBroadcast(userId, data)
      Ok("")
    }
  }

  def sendToUser() = Action.async { request =>
    future{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUser(userId, data)
      Ok("")
    }
  }

  def sendToAllUsers() = Action.async { request =>
    future{
      val req = request.body.asJson.get.asInstanceOf[JsArray]
      notificationRouter.sendToAllUsers(req)
      Ok("")
    }
  }

  def connectedClientCount() = Action { request =>
    Ok(notificationRouter.connectedSockets.toString)
  }

}
