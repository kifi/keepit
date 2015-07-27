package com.keepit.eliza.controllers.internal

import com.keepit.common.akka.SafeFuture
import com.keepit.eliza._
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.logging.Logging
import com.keepit.model.{ Username, Library, User }
import com.keepit.common.db.{ ExternalId, Id }
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
    val category = LibraryPushNotificationCategory((req \ "category").as[String])
    val pushNotificationExperiment = (req \ "pushNotificationExperiment").as[PushNotificationExperiment]
    val libraryId = (req \ "libraryId").as[Id[Library]]
    val libraryUrl = (req \ "libraryUrl").as[String]
    val force = (req \ "force").asOpt[Boolean] //backward compatibility. remove option when done deploing shoebox
    messagingCommander.sendLibraryPushNotification(userId, message, libraryId, libraryUrl, pushNotificationExperiment, category, force.getOrElse(false)).map { deviceCount =>
      Ok(JsNumber(deviceCount))
    }
  }

  def sendUserPushNotification() = Action.async { request =>
    val req = request.body.asJson.get.as[JsObject]
    val userId = Id[User]((req \ "userId").as[Long])
    val message = (req \ "message").as[String]
    val pushNotificationExperiment = (req \ "pushNotificationExperiment").as[PushNotificationExperiment]
    val recipientExtId = (req \ "recipientId").as[ExternalId[User]]
    val category = UserPushNotificationCategory((req \ "category").as[String])
    val username = (req \ "username").as[Username]
    val pictureUrl = (req \ "pictureUrl").as[String]
    messagingCommander.sendUserPushNotification(userId, message, recipientExtId, username: Username, pictureUrl, pushNotificationExperiment, category).map { deviceCount =>
      Ok(JsNumber(deviceCount))
    }
  }

  def sendGeneralPushNotification() = Action.async { request =>
    val req = request.body.asJson.get.as[JsObject]
    val userId = Id[User]((req \ "userId").as[Long])
    val message = (req \ "message").as[String]
    val category = SimplePushNotificationCategory((req \ "category").as[String])
    val pushNotificationExperiment = (req \ "pushNotificationExperiment").as[PushNotificationExperiment]
    val force = (req \ "force").asOpt[Boolean] //backward compatibility. remove option when done deploing shoebox
    messagingCommander.sendGeneralPushNotification(userId, message, pushNotificationExperiment, category, force.getOrElse(false)).map { deviceCount =>
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

  def getSharedThreadsForGroupByWeek = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[Seq[Id[User]]]
    val threadStats = elizaStatsCommander.getSharedThreadsForGroupByWeek(userIds)
    log.info(s"[ELIZA DEBUG] Eliza thinks there are ${threadStats.map(_.numUsers).sum} shared threads for group $userIds")
    Ok(Json.toJson(threadStats))
  }

  def getAllThreadsForGroupByWeek = Action(parse.tolerantJson) { request =>
    val userIds = request.body.as[Seq[Id[User]]]
    val threadStats = elizaStatsCommander.getAllThreadsForGroupByWeek(userIds)
    log.info(s"[ELIZA DEBUG] Eliza thinks there are ${threadStats.map(_.numUsers).sum} all threads for group $userIds")
    Ok(Json.toJson(threadStats))
  }

}
