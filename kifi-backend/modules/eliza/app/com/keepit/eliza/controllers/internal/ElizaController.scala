package com.keepit.eliza.controllers.internal

import com.keepit.eliza.{ PushNotificationCategory, PushNotificationExperiment }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.logging.Logging
import com.keepit.model.{ User }
import com.keepit.common.db.{ Id }
import com.keepit.realtime._

import scala.concurrent.future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.mvc.Action
import play.api.libs.json.{ Json, JsObject, JsArray }

import com.google.inject.Inject
import com.keepit.eliza.commanders.{ MessagingCommander, NotificationJson, NotificationCommander, ElizaStatsCommander }
import com.keepit.eliza.model.UserThreadStats
import com.keepit.common.db.slick._

class ElizaController @Inject() (
    notificationRouter: WebSocketRouter,
    messagingCommander: MessagingCommander,
    deviceRepo: DeviceRepo,
    db: Database,
    elizaStatsCommander: ElizaStatsCommander) extends ElizaServiceController with Logging {

  def disableDevice(id: Id[Device]) = Action { request =>
    val device = db.readWrite { implicit s =>
      val device = deviceRepo.get(id)
      deviceRepo.save(device.copy(state = DeviceStates.INACTIVE))
    }
    Ok(device.toString)
  }

  def getUserThreadStats(userId: Id[User]) = Action { request =>
    Ok(UserThreadStats.format.writes(elizaStatsCommander.getUserThreadStats(userId)))
  }

  def sendPushNotification() = Action.async { request =>
    future {
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val message = (req \ "message").asInstanceOf[String]
      val pushNotificationExperiment = (req \ "pushNotificationExperiment").asInstanceOf[PushNotificationExperiment]
      val pushNotificationCategory = (req \ "pushNotificationCategory").asInstanceOf[PushNotificationCategory]
      messagingCommander.sendPushNotification(userId, message, pushNotificationCategory, pushNotificationExperiment)
      Ok("")
    }
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
