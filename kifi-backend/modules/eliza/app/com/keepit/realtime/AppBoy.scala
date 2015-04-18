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
import play.api.libs.json._

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

  // When this is the only push provider, refactor this to just take userId and notification. No need to get list of devices.
  def notifyUser(userId: Id[User], allDevices: Seq[Device], notification: PushNotification, force: Boolean): Future[Int] = {
    log.info(s"[AppBoy] Notifying user: $userId with $allDevices")
    val activeDevices = if (force) allDevices else allDevices.filter(_.state == DeviceStates.ACTIVE)
    val deviceTypes = activeDevices.groupBy(_.deviceType).keys.toList

    shoeboxClient.getUser(userId).map { userOpt =>
      userOpt match {
        case Some(user) if deviceTypes.nonEmpty =>
          sendNotification(user, deviceTypes, notification, force)
          log.info(s"[AppBoy] sent user $userId push notifications to ${deviceTypes.length} device types out of ${allDevices.size}. $notification")
          deviceTypes.length
        case Some(user) =>
          log.info(s"[AppBoy] no devices for $userId push notifications $allDevices devices. notification: $notification")
          0
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
      case mcpn: MessageCountPushNotification =>
        json
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

  private def sendNotification(user: User, deviceTypes: Seq[DeviceType], notification: PushNotification, wasForced: Boolean): Unit = {
    val userId = user.id.get

    val json = Json.obj(
      "app_group_id" -> AppBoyConfig.appGroupId,
      "external_user_ids" -> Json.toJson(Seq(user.externalId)),
      "messages" -> Json.obj(
        "apple_push" -> Json.obj(
          "badge" -> notification.unvisitedCount,
          "sound" -> Json.toJson(notification.sound),
          "alert" -> notification.message,
          "content-available" -> (if (notification.message.isDefined) false else true),
          "extra" -> addExtraJson(notification, DeviceType.IOS)
        ),
        "android_push" -> (Json.obj(
          "badge" -> notification.unvisitedCount,
          "sound" -> Json.toJson(notification.sound),
          "alert" -> notification.message,
          "content-available" -> (if (notification.message.isDefined) false else true),
          "extra" -> addExtraJson(notification, DeviceType.Android)
        ) ++ (if (notification.message.isDefined) Json.obj("title" -> Json.toJson(notification.message.get)) else Json.obj()))
      )
    )

    notification match {
      case spn: SimplePushNotification =>
        log.info(s"[AppBoy] sending SimplePushNotification to user $userId with: $json")
      case mtpn: MessageThreadPushNotification =>
        log.info(s"[AppBoy] sending MessageThreadPushNotification to user $userId message ${mtpn.id} with $json")
      case lupn: LibraryUpdatePushNotification =>
        log.info(s"[AppBoy] sending LibraryUpdatePushNotification to user $userId library ${lupn.libraryId} message ${lupn.message} with $json")
      case upn: UserPushNotification =>
        log.info(s"[AppBoy] sending UserPushNotification to user $userId user ${upn.username}:${upn.userExtId} message ${upn.message} with $json")
      case mcpn: MessageCountPushNotification =>
        log.info(s"[AppBoy] sending MessageCountPushNotification to user $userId with $json")
    }

    RetryFuture(attempts = 3, {
      case error: Throwable =>
        log.error(s"[AppBoy] Error when pushing $notification for user $userId. Will retry. Error: ${error.getClass.getSimpleName} $error", error)
        true
    })(client.send(json, notification)).onComplete {
      case Success(res) if res.status / 100 == 2 =>
        log.info(s"[AppBoy] successful push notification to user $userId: ${res.body}")
        deviceTypes.foreach { deviceType =>
          messagingAnalytics.sentPushNotification(userId, deviceType, notification)
        }
      case Success(non200) =>
        if (!wasForced) airbrake.notify(s"[AppBoy] bad status ${non200.status} on push notification $notification for user $userId. response: ${non200.body}")
      case Failure(e) =>
        if (!wasForced) airbrake.notify(s"[AppBoy] fail to send push notification $notification, json $json for user $userId - error: ${e.getClass.getSimpleName} $e")
    }
  }

}
