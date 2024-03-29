package com.keepit.realtime

import com.google.inject.Inject
import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.NonOKResponseException
import com.keepit.eliza.{ OrgPushNotificationCategory, LibraryPushNotificationCategory, UserPushNotificationCategory }
import com.keepit.eliza.commanders.MessagingAnalytics
import com.keepit.model.{ Library, User }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object AppBoyConfig {
  val baseUrl: String = "https://api.appboy.com"
  val prodAppGroupId: String = "4212bbb0-d07b-4109-986a-aac019d8062a"
  val devAppGroupId: String = "c874c981-3cb1-45fd-9ec1-40d3eb8a73ee"
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

  // When this is the only push provider, refactor this to just take userId and notification. No need to get list of devices. // 1/7/16 this is the only push provider, but seems like allDevices is being used quite a bit
  def notifyUser(userId: Id[User], allDevices: Seq[Device], notification: PushNotification, force: Boolean): Future[Int] = {
    log.info(s"[AppBoy] Notifying user: $userId with $allDevices")
    val activeDevices = if (force) allDevices else allDevices.filter(_.state == DeviceStates.ACTIVE)

    shoeboxClient.getUser(userId).map { userOpt =>
      userOpt match {
        case Some(user) if activeDevices.nonEmpty =>
          sendNotification(user, activeDevices, notification, force)
          log.info(s"[AppBoy] sent user $userId push notifications to ${activeDevices.length} device types out of ${allDevices.size}. $notification")
          activeDevices.length
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
        json.as[JsObject] + ("id" -> Json.toJson(mtpn.id))
      case lupn: LibraryUpdatePushNotification =>
        val pushType = lupn.category match {
          case LibraryPushNotificationCategory.LibraryChanged => "lr"
          case LibraryPushNotificationCategory.LibraryInvitation => "li"
          case _ => throw new Exception(s"unsupported library push notification category ${lupn.category.name}")
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
          case UserPushNotificationCategory.UserConnectionAccepted => "us"
          case UserPushNotificationCategory.ContactJoined => "us"
          case UserPushNotificationCategory.NewLibraryFollower => "nf"
          case UserPushNotificationCategory.NewLibraryCollaborator => "us"
          case UserPushNotificationCategory.LibraryInviteAccepted => "us"
          case UserPushNotificationCategory.NewOrganizationMember => "om"
          case _ => throw new Exception(s"unsupported user push notification category ${upn.category.name}")

        }
        json.as[JsObject] ++ Json.obj("t" -> pushType, "uid" -> upn.userExtId, "un" -> upn.username.value, "purl" -> upn.pictureUrl)
      case opn: OrganizationPushNotification =>
        val pushType = opn.category match {
          case OrgPushNotificationCategory.OrganizationInvitation => "oi"
          case _ => throw new Exception(s"unsupported org push notification category ${opn.category.name}")
        }
        json.as[JsObject] ++ Json.obj("t" -> pushType)
      case _ =>
        throw new Exception(s"Don't recognize push notification $notification")
    }
  }

  private def sendNotification(user: User, devices: Seq[Device], notification: PushNotification, wasForced: Boolean): Unit = {
    val userId = user.id.get

    val devDevices = devices.filter(_.isDev == true)
    val prodDevices = devices.filter(_.isDev == false)

    val json = Json.obj(
      // app_group_id is added before pushing to prod / dev devices so as to push
      // to all devices able to get this push
      "external_user_ids" -> Json.toJson(Seq(user.externalId)),
      "campaign_id" -> "2c22f953-902a-4f3c-88f0-34fe07edeccf",
      "messages" -> Json.obj(
        "apple_push" -> Json.obj(
          "message_variation_id" -> "iosPush-9",
          "badge" -> notification.unvisitedCount,
          "sound" -> Json.toJson(notification.sound),
          "alert" -> notification.message,
          "content-available" -> (if (notification.message.isDefined) false else true),
          "extra" -> addExtraJson(notification, DeviceType.IOS)
        ),
        "android_push" -> (Json.obj(
          "message_variation_id" -> "androidPush-12",
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
      case opn: OrganizationPushNotification =>
        log.info(s"[AppBoy] sending OrgPushNotification to user $userId message ${opn.message} with $json")
    }

    if (prodDevices.size > 0) {
      val prodJson = Json.obj("app_group_id" -> AppBoyConfig.prodAppGroupId) ++ json
      trySendNotification(userId, prodDevices, notification, prodJson, wasForced)
    }

    if (devDevices.size > 0) {
      val devJson = Json.obj("app_group_id" -> AppBoyConfig.devAppGroupId) ++ json
      trySendNotification(userId, devDevices, notification, devJson, wasForced)
    }

  }

  private def trySendNotification(userId: Id[User], devices: Seq[Device], notification: PushNotification, json: JsObject, wasForced: Boolean): Unit = {
    RetryFuture(attempts = 3, {
      case error: Throwable =>
        log.error(s"[AppBoy] Error when pushing $notification for user $userId. Will retry. Error: ${error.getClass.getSimpleName} $error", error)
        true
    })(client.send(json, notification)).onComplete {
      case Success(res) if res.status / 100 == 2 =>
        log.info(s"[AppBoy] successful push notification to user $userId: ${res.body}")
        devices.foreach { device =>
          messagingAnalytics.sentPushNotification(userId, device.deviceType, notification)
        }
      case Success(non200) =>
        if (!wasForced) airbrake.notify(s"[AppBoy] bad status ${non200.status} on push notification $notification for user $userId. response: ${non200.body}")
      case Failure(e) =>
        if (!wasForced) {
          e match {
            case statError: NonOKResponseException if statError.response.status / 100 == 4 =>
              db.readWrite { implicit s =>
                devices foreach { device =>
                  deviceRepo.save(device.copy(state = DeviceStates.REJECTED_BY_APPBOY))
                }
              }
              log.warn(s"[AppBoy] 4xx error from server, disabling device for user $userId. fail to send push notification $notification, json $json - error: ${e.getClass.getSimpleName} $e")
            case _ =>
              airbrake.notify(s"[AppBoy] fail to send push notification $notification, json $json for user $userId - error: ${e.getClass.getSimpleName} $e")
          }
        }
    }
  }

}
