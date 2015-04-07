package com.keepit.realtime

import com.google.inject.Inject
import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.eliza.{ LibraryPushNotificationCategory, UserPushNotificationCategory }
import com.keepit.eliza.commanders.MessagingAnalytics
import com.keepit.model.{ Library, User }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.{ JsNumber, JsString, JsObject, Json }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object AppBoyConfig {
  val baseUrl: String = "https://api.appboy.com"
  val appGroupId: String = "4212bbb0-d07b-4109-986a-aac019d8062a"
}

class AppBoy @Inject() (
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

    val appBoySign = "ab_" + signature

    val updatedDevice = db.readWrite { implicit s =>

      // inactivate devices that have non-appboy signature
      deviceRepo.getByUserIdAndDeviceType(userId, deviceType).collect {
        case device if !device.signature.exists(_.startsWith("ab_")) =>
          // We only want non-AppBoy devices
          deviceRepo.save(device.copy(state = DeviceStates.INACTIVE))
      }

      deviceRepo.getByUserIdAndDeviceTypeAndSignature(userId, deviceType, appBoySign, None) match {
        case Some(d) => // update or reactivate an existing device
          deviceRepo.save(d.copy(token = None, isDev = isDev, state = DeviceStates.ACTIVE))
        case None => // new device for user! save new device!
          deviceRepo.save(Device(userId = userId, token = None, deviceType = deviceType, isDev = isDev, signature = Some(appBoySign)))
      }
    }

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

  // see https://docs.google.com/a/kifi.com/document/d/1efEGk8Wdj2dAjWjUWvsHW5UC0p2srjIXiju8tLpuOMU/edit# for spec
  private def addExtraJson(notification: PushNotification, deviceType: DeviceType) = {
    val json = Json.obj("unreadCount" -> notification.unvisitedCount)
    notification match {
      case spn: SimplePushNotification =>
        json
      case mtpn: MessageThreadPushNotification =>
        json.as[JsObject] + ("id" -> JsString(mtpn.id.id))
      case lupn: LibraryUpdatePushNotification =>
        val pushType = lupn.category match {
          case LibraryPushNotificationCategory.LibraryChanged => "lr"
          case LibraryPushNotificationCategory.LibraryInvitation => "li"
          case _ => "lr"
        }
        val withLid = json.as[JsObject] ++ Json.obj("t" -> pushType, "lid" -> Library.publicId(lupn.libraryId).id)
        deviceType match {
          case DeviceType.Android =>
            withLid
          case DeviceType.IOS =>
            withLid + ("lu" -> JsString(lupn.libraryUrl))
        }
      case upn: UserPushNotification =>
        val pushType = upn.category match {
          case UserPushNotificationCategory.UserConnectionRequest => "fr"
          case UserPushNotificationCategory.ContactJoined => "us"
          case UserPushNotificationCategory.NewLibraryFollower => "nf"
          case _ => "us"
        }
        json.as[JsObject] ++ Json.obj("t" -> pushType, "uid" -> upn.userExtId, "un" -> upn.username.value, "purl" -> upn.pictureUrl)
      case _ =>
        throw new Exception(s"Don't recognize push notification $notification")
    }
  }

  private def sendNotification(user: User, device: Device, notification: PushNotification): Unit = {

    val defaultPushJson = Json.obj(
      "badge" -> notification.unvisitedCount,
      "sound" -> Json.toJson(notification.sound),
      "content-available" -> false,
      "alert" -> notification.message,
      "extra" -> addExtraJson(notification, device.deviceType)
    )

    val (deviceMsgType, devicePushJson) = device.deviceType match {
      case DeviceType.IOS =>
        val applePushJson = notification.message match {
          case Some(msg) => defaultPushJson
          case None => defaultPushJson ++ Json.obj("content-available" -> true)
        }
        ("apple_push", applePushJson)
      case DeviceType.Android =>
        val androidPushJson = defaultPushJson ++ Json.obj("title" -> notification.message)
        ("android_push", androidPushJson)
    }

    val json = Json.obj(
      "app_group_id" -> AppBoyConfig.appGroupId,
      "external_user_ids" -> Json.toJson(Seq(user.externalId)),
      "messages" -> Json.obj(
        deviceMsgType -> devicePushJson
      )
    )

    notification match {
      case spn: SimplePushNotification =>
        log.info(s"[AppBoy] sending SimplePushNotification to user ${device.userId} device [${device.token}] with: $json")
      case mtpn: MessageThreadPushNotification =>
        log.info(s"[AppBoy] sending MessageThreadPushNotification to user ${device.userId} device: [${device.token}] message ${mtpn.id} with $json")
      case lupn: LibraryUpdatePushNotification =>
        log.info(s"[AppBoy] sending LibraryUpdatePushNotification to user ${device.userId} device: [${device.token}] library ${lupn.libraryId} message ${lupn.message} with $json")
      case upn: UserPushNotification =>
        log.info(s"[AppBoy] sending UserPushNotification to user ${device.userId} device: [${device.token}] user ${upn.username}:${upn.userExtId} message ${upn.message} wtih $json")
    }

    RetryFuture(attempts = 3, {
      case error: Throwable =>
        airbrake.notify(s"[AppBoy] Error when pushing $notification for device ${device.id}. Will retry. Error: ${error.getClass.getSimpleName} $error")
        true
    })(client.send(json, device, notification)).onComplete {
      case Success(res) if res.status / 100 == 2 =>
        log.info(s"[AppBoy] successful push notification to device $device: ${res.body}")
        messagingAnalytics.sentPushNotification(device, notification)
      case Success(non200) =>
        airbrake.notify(s"[AppBoy] bad status ${non200.status} on push notification $notification for device $device. response: ${non200.body}")
      case Failure(e) =>
        airbrake.notify(s"[AppBoy] fail to send push notification $notification for device $device - error: ${e.getClass.getSimpleName} $e")
    }
  }

}
