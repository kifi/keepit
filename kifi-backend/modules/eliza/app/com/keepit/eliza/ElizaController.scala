package com.keepit.eliza

import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.logging.Logging
import com.keepit.model.{User}
import com.keepit.common.db.{Id}

import scala.concurrent.future
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.mvc.Action
import play.api.libs.json.{JsObject, JsArray}

import com.google.inject.Inject


class ElizaController @Inject() (notificationRouter: NotificationRouter) extends ElizaServiceController with Logging {

  def sendToUserNoBroadcast() = Action { request =>
    Async(future{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUserNoBroadcast(userId, data)
      Ok("")
    })
  }

  def sendToUser() = Action { request =>
    Async(future{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUser(userId, data)
      Ok("")
    })
  }

  def sendToAllUsers() = Action { request =>
    Async(future{
      val req = request.body.asJson.get.asInstanceOf[JsArray]
      notificationRouter.sendToAllUsers(req)
      Ok("")
    })
  }

}
