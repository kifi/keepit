package com.keepit.realtime

import com.google.inject.Inject
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.eliza.commanders.MessagingAnalytics
import com.keepit.model.{ Library, User }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.{ JsString, JsObject, Json }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object AppBoyConfig {
  val baseUrl: String = "https://api.appboy.com"
  val appGroupId: String = "4212bbb0-d07b-4109-986a-aac019d8062a"
}

class AppBoyImpl @Inject() (
    client: AppBoyClient,
    db: Database,
    deviceRepo: DeviceRepo,
    shoeboxClient: ShoeboxServiceClient,
    messagingAnalytics: MessagingAnalytics,
    airbrake: AirbrakeNotifier,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends Logging {

  def registerDevice(userId: Id[User], deviceType: DeviceType, isDev: Boolean, signature: String): Device = {
    log.info(s"[AppBoy] Registering device: $deviceType:(no token) for (user $userId, signature $signature)")

    val updatedDevice = db.readWrite { implicit s =>
      deviceRepo.getByUserIdAndDeviceTypeAndSignature(userId, deviceType, signature, None) match {
        case Some(d) => // update or reactivate an existing device
          deviceRepo.save(d.copy(token = None, isDev = isDev, state = DeviceStates.ACTIVE))
        case None => // new device for user! save new device!
          deviceRepo.save(Device(userId = userId, token = None, deviceType = deviceType, isDev = isDev, signature = Some(signature)))
      }
    }
    // inactivate any devices?

    updatedDevice
  }

  def notifyUser(userId: Id[User], allDevices: Seq[Device], notification: PushNotification): Future[Int] = {
    log.info(s"[AppBoy] Notifying user: $userId with $allDevices")
    val (activeDevices, _) = allDevices.partition(d => d.state == DeviceStates.ACTIVE)

    shoeboxClient.getUser(userId).map { userOpt =>
      userOpt match {
        case Some(user) =>
          activeDevices foreach { device =>
            sendNotification(user, device, notification)
          }
          log.info(s"[AppBoy] sent user $userId push notifications to ${activeDevices.size} active devices out of ${allDevices.size}. $notification")
          activeDevices.size

        case None =>
          airbrake.notify(s"[AppBoy] user $userId not found to send push notifications! $allDevices devices. notification: $notification)")
          0
      }
    }
  }

  private def addExtraJson(notification: PushNotification, deviceType: DeviceType) = {
    val json = Json.obj("unreadCount" -> notification.unvisitedCount)
    notification match {
      case spn: SimplePushNotification =>
        json
      case mtpn: MessageThreadPushNotification =>
        json.as[JsObject] + ("id" -> JsString(mtpn.id.id))
      case lupn: LibraryUpdatePushNotification =>
        val withLid = json.as[JsObject] ++ Json.obj("t" -> "lr", "lid" -> JsString(Library.publicId(lupn.libraryId).id))
        deviceType match {
          case DeviceType.Android =>
            withLid
          case DeviceType.IOS =>
            withLid + ("lu" -> JsString(lupn.libraryUrl))
        }
      case _ =>
        throw new Exception(s"Don't recognize push notification $notification")
    }
  }

  private def sendNotification(user: User, device: Device, notification: PushNotification): Unit = {
    val deviceMsgType = device.deviceType match {
      case DeviceType.IOS => "apple_push"
      case DeviceType.Android => "android_push"
    }
    val extraJson = addExtraJson(notification, device.deviceType)

    val json = Json.obj(
      "app_group_id" -> AppBoyConfig.appGroupId,
      "external_user_ids" -> Json.toJson(Seq(user.externalId)),
      "messages" -> Json.obj(
        deviceMsgType -> Json.obj(
          "sound" -> Json.toJson(notification.sound),
          "title" -> notification.message,
          "alert" -> notification.message,
          "extra" -> extraJson
        )
      )
    )

    notification match {
      case spn: SimplePushNotification =>
        log.info(s"[AppBoy] sending SimplePushNotification to user ${device.userId} device [${device.token}] with: $json")
      case mtpn: MessageThreadPushNotification =>
        log.info(s"[AppBoy] sending MessageThreadPushNotification to user ${device.userId} device: [${device.token}] message ${mtpn.id}")
      case lupn: LibraryUpdatePushNotification =>
        log.info(s"[AppBoy] sending LibraryUpdatePushNotification to user ${device.userId} device: [${device.token}] library ${lupn.libraryId} message ${lupn.message}")
    }

    client.send(json, device, notification) andThen {
      case Success(res) =>
        if (res.status / 100 != 2) {
          airbrake.notify(s"[AppBoy] bad status ${res.status} on push notification $notification for device $device. response: ${res.body}")
        } else {
          log.info(s"[AppBoy] successful push notification to device $device: ${res.body}")
          messagingAnalytics.sentPushNotification(device, notification)
        }
      case Failure(e) =>
        airbrake.notify(s"[AppBoy] fail to send push notification $notification for device $device")
    }
  }

}
