package com.keepit.realtime

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.strings._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient, NonOKResponseException }
import com.keepit.common.time._
import com.keepit.eliza.model._
import com.keepit.model.User
import org.joda.time.{ DateTime, Days }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.{ Future, future }

case class UrbanAirshipConfig(key: String, secret: String, devKey: String, devSecret: String, baseUrl: String = "https://go.urbanairship.com")

// Add fields to this object and handle them properly for each platform
case class PushNotification(id: ExternalId[MessageThread], unvisitedCount: Int, message: Option[String], sound: Option[NotificationSound])

case class NotificationSound(name: String) extends AnyVal

object UrbanAirship {
  val DefaultNotificationSound = NotificationSound("notification.aiff")
  val MoreMessageNotificationSound = NotificationSound("newnotificationoutsidemessage.aiff")
  val RecheckPeriod = Days.ONE
}

@ImplementedBy(classOf[UrbanAirshipImpl])
trait UrbanAirship {
  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType, isDev: Boolean): Device
  def notifyUser(userId: Id[User], notification: PushNotification): Unit
  def sendNotification(device: Device, notification: PushNotification): Unit
}

class UrbanAirshipImpl @Inject() (
    client: UrbanAirshipClient,
    deviceRepo: DeviceRepo,
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock) extends UrbanAirship with Logging {

  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType, isDev: Boolean): Device = synchronized {
    log.info(s"Registering device: $token (user $userId)")
    val device = db.readWrite { implicit s =>
      deviceRepo.get(token, deviceType).map { d =>
        if (d.userId != userId) deviceRepo.save(d.copy(state = DeviceStates.INACTIVE))
      }
      deviceRepo.get(userId, token, deviceType) match {
        case Some(d) if d.state == DeviceStates.ACTIVE && d.isDev == isDev => d
        case Some(d) => deviceRepo.save(d.copy(state = DeviceStates.ACTIVE, isDev = isDev))
        case None => deviceRepo.save(Device(userId = userId, token = token, deviceType = deviceType, isDev = isDev))
      }
    }
    future {
      val devices = db.readOnlyReplica { implicit s => deviceRepo.getByUserId(userId) }
      devices foreach client.updateDeviceState
    }
    device
  }

  def notifyUser(userId: Id[User], notification: PushNotification): Unit = {
    // todo: Check shoebox if user has a notification preference
    // UserNotifyPreferenceRepo.canSend(userId, someIdentifierRepresentingMobileNotificationType)
    log.info(s"Notifying user: $userId")
    for {
      d <- db.readOnlyReplica { implicit s => deviceRepo.getByUserId(userId) }
      device <- client.updateDeviceState(d) if device.state == DeviceStates.ACTIVE
    } {
      sendNotification(device, notification)
    }
  }

  //see http://docs.urbanairship.com/reference/api/v3/push.html
  private def createAndroidJson(notification: PushNotification, device: Device) = {
    notification.message.map { message =>
      Json.obj(
        "audience" -> Json.obj("device_token" -> device.token),
        "device_types" -> Json.arr("android"),
        "notification" -> Json.obj(
          "android" -> Json.obj(
            "alert" -> message,
            "extra" -> Json.obj(
              "unreadCount" -> notification.unvisitedCount.toString,
              "id" -> notification.id.id
            )
          )
        )
      )
    } getOrElse {
      Json.obj(
        "audience" -> Json.obj("device_token" -> device.token),
        "device_types" -> Json.arr("android"),
        "notification" -> Json.obj(
          "android" -> Json.obj(
            "extra" -> Json.obj(
              "unreadCount" -> notification.unvisitedCount.toString,
              "id" -> notification.id.id
            )
          )
        )
      )
    }
  }

  //see http://docs.urbanairship.com/reference/api/v3/push.html
  private def createIosJson(notification: PushNotification, device: Device) =
    notification.message.map { message =>
      Json.obj(
        "audience" -> Json.obj("device_token" -> device.token),
        "device_types" -> Json.arr("ios"),
        "notification" -> Json.obj(
          "ios" -> Json.obj(
            "alert" -> message.abbreviate(1000), //can be replaced with a json https://developer.apple.com/library/mac/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/ApplePushService.html#//apple_ref/doc/uid/TP40008194-CH100-SW9
            "badge" -> notification.unvisitedCount,
            "sound" -> notification.sound.get.name,
            "content-available" -> true,
            "extra" -> Json.obj(
              "unreadCount" -> notification.unvisitedCount,
              "id" -> notification.id.id
            )
          )
        )
      )
    } getOrElse {
      Json.obj(
        "audience" -> Json.obj("device_token" -> device.token),
        "device_types" -> Json.arr("ios"),
        "notification" -> Json.obj(
          "ios" -> Json.obj(
            "badge" -> notification.unvisitedCount,
            "content-available" -> false,
            "extra" -> Json.obj(
              "unreadCount" -> notification.unvisitedCount,
              "id" -> notification.id.id
            )
          )
        )
      )
    }

  def sendNotification(device: Device, notification: PushNotification): Unit = {
    log.info(s"Sending notification to device: ${device.token}")

    val json = device.deviceType match {
      case DeviceType.IOS => createIosJson(notification, device)
      case DeviceType.Android => createAndroidJson(notification, device)
    }
    client.send(json, device, notification)
  }

}
