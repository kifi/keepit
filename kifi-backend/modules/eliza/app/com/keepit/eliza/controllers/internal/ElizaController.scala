package com.keepit.eliza.controllers.internal

import com.keepit.common.akka.SafeFuture
import com.keepit.eliza.{ PushNotificationCategory, PushNotificationExperiment }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.logging.Logging
import com.keepit.model.{ Username, Library, User }
import com.keepit.common.db.{ Id }
import com.keepit.realtime._

import scala.concurrent.future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.mvc.Action
import play.api.libs.json.{ JsNumber, Json, JsObject, JsArray }

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

  def sendLibraryPushNotification() = Action.async { request =>
    val req = request.body.asJson.get.as[JsObject]
    val userId = Id[User]((req \ "userId").as[Long])
    val message = (req \ "message").as[String]
    val pushNotificationExperiment = (req \ "pushNotificationExperiment").as[PushNotificationExperiment]
    val libraryId = (req \ "libraryId").as[Id[Library]]
    val libraryUrl = (req \ "libraryUrl").as[String]
    messagingCommander.sendLibraryPushNotification(userId, message, libraryId, libraryUrl, pushNotificationExperiment).map { deviceCount =>
      Ok(JsNumber(deviceCount))
    }
  }
  //val payload = Json.obj("userId" -> userId, "message" -> message, "recipientUserId" -> recipientUserId, "username" -> username.value, "pictureUrl" -> pictureUrl, "pushNotificationExperiment" -> pushNotificationExperiment)
  def sendUserPushNotification() = Action.async { request =>
    val req = request.body.asJson.get.as[JsObject]
    val userId = Id[User]((req \ "userId").as[Long])
    val message = (req \ "message").as[String]
    val pushNotificationExperiment = (req \ "pushNotificationExperiment").as[PushNotificationExperiment]
    val recipientUserId = (req \ "recipientUserId").as[Id[User]]
    val username = (req \ "pictureUrl").as[Username]
    val pictureUrl = (req \ "pictureUrl").as[String]
    messagingCommander.sendUserPushNotification(userId, message, recipientUserId, username: Username, pictureUrl, pushNotificationExperiment).map { deviceCount =>
      Ok(JsNumber(deviceCount))
    }
  }

  def sendGeneralPushNotification() = Action.async { request =>
    val req = request.body.asJson.get.as[JsObject]
    val userId = Id[User]((req \ "userId").as[Long])
    val message = (req \ "message").as[String]
    val pushNotificationExperiment = (req \ "pushNotificationExperiment").as[PushNotificationExperiment]
    messagingCommander.sendGeneralPushNotification(userId, message, pushNotificationExperiment).map { deviceCount =>
      Ok(JsNumber(deviceCount))
    }
  }

  def sendToUserNoBroadcast() = Action.async { request =>
    val req = request.body.asJson.get.as[JsObject]
    val userId = Id[User]((req \ "userId").as[Long])
    val data = (req \ "data").as[JsArray]
    SafeFuture {
      notificationRouter.sendToUserNoBroadcast(userId, data)
      Ok("")
    }
  }

  def sendToUser() = Action.async { request =>
    val req = request.body.asJson.get.as[JsObject]
    val userId = Id[User]((req \ "userId").as[Long])
    val data = (req \ "data").as[JsArray]
    SafeFuture {
      notificationRouter.sendToUser(userId, data)
      Ok("")
    }
  }

  def sendToAllUsers() = Action.async { request =>
    val req = request.body.asJson.get.as[JsArray]
    SafeFuture {
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
