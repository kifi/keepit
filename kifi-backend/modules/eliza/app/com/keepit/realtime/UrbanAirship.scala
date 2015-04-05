package com.keepit.realtime

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.eliza.{ LibraryPushNotificationCategory, UserPushNotificationCategory }
import com.keepit.eliza.commanders.MessagingAnalytics

import scala.concurrent.duration._

import akka.actor.Scheduler
import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.eliza.model._
import com.keepit.model.{ Library, User }
import org.joda.time.Days
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import scala.concurrent.{ Future }
import scala.util.{ Try, Success, Failure }

case class UrbanAirshipConfig(key: String, secret: String, devKey: String, devSecret: String, baseUrl: String = "https://go.urbanairship.com")

class UrbanAirship @Inject() (
    client: UrbanAirshipClient,
    deviceRepo: DeviceRepo,
    airbrake: AirbrakeNotifier,
    messagingAnalytics: MessagingAnalytics,
    db: Database,
    clock: Clock,
    implicit val publicIdConfig: PublicIdConfiguration,
    scheduler: Scheduler) extends Logging {

  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType, isDev: Boolean, signatureOpt: Option[String]): Device = synchronized {
    log.info(s"[UrbanAirship] Registering device: $deviceType:$token for (user $userId, signature $signatureOpt)")

    val device = if (signatureOpt.isDefined) {
      val signature = signatureOpt.get

      // find all devices for user with deviceType, but no signature and deactivate them!
      db.readWrite { implicit s =>
        val noSignatureDevices = deviceRepo.getByUserIdAndDeviceType(userId, deviceType).filter(_.signature.isEmpty)
        noSignatureDevices.map { d =>
          log.info(s"[UrbanAirship] deactivate old devices for user $userId: (device: ${d.deviceType}}, token: ${d.token}})")
          deviceRepo.save(d.copy(state = DeviceStates.INACTIVE))
        }
      }

      val targetDevice = db.readOnlyMaster { implicit s =>
        deviceRepo.getByUserIdAndDeviceTypeAndSignature(userId, deviceType, signature, None)
      }
      log.info(s"[UrbanAirship] search by (userId $userId, deviceType $deviceType, signature $signature) => found: $targetDevice")
      targetDevice match {
        case Some(d) =>
          log.info(s"[UrbanAirship] reactivate/update device for user $userId: (device: $deviceType, signature: $signature) with token $token")
          db.readWrite { implicit s =>
            deviceRepo.save(d.copy(token = Some(token), isDev = isDev, state = DeviceStates.ACTIVE))
          }
        case None =>
          log.info(s"[UrbanAirship] save new device for user $userId: (device: $deviceType, signature: $signature) with token $token")
          db.readWrite { implicit s =>
            deviceRepo.save(Device(userId = userId, token = Some(token), deviceType = deviceType, isDev = isDev, signature = signatureOpt))
          }
      }

    } else { // no signature provided... can only deal with tokens (old logic)
      db.readWrite { implicit s =>
        // deactivate all devices with token & deviceType but don't match current userId and don't have signature
        deviceRepo.get(token, deviceType).filter(d => d.userId != userId && d.signature.isEmpty).map { d =>
          log.info(s"[UrbanAirship] deactivate old devices for user $userId: (device: ${d.deviceType}}, token: ${d.token}})")
          deviceRepo.save(d.copy(state = DeviceStates.INACTIVE))
        }
        // find device with token & device type
        deviceRepo.get(userId, token, deviceType) match {
          case Some(d) if d.state == DeviceStates.ACTIVE && d.isDev == isDev => d
          case Some(d) => deviceRepo.save(d.copy(state = DeviceStates.ACTIVE, isDev = isDev))
          case None => deviceRepo.save(Device(userId = userId, token = Some(token), deviceType = deviceType, isDev = isDev))
        }
      }
    }
    Future {
      val devices = db.readOnlyReplica { implicit s => deviceRepo.getByUserId(userId) }
      devices foreach client.updateDeviceState
    }
    device
  }

  def getDevices(userId: Id[User]): Seq[Device] = {
    db.readOnlyMaster { implicit s =>
      deviceRepo.getByUserId(userId)
    }
  }

  def notifyUser(userId: Id[User], allDevices: Seq[Device], notification: PushNotification): Future[Int] = {
    log.info(s"[UrbanAirship] Notifying user: $userId with $allDevices")
    //get only active devices
    val activeDevices = allDevices filter { d =>
      d.state == DeviceStates.ACTIVE
    }
    //send them all a push notification
    activeDevices foreach { device =>
      sendNotification(device, notification)
    }
    log.info(s"[UrbanAirship] user $userId has ${activeDevices.size} active devices out of ${allDevices.size} for notification $notification")
    //refresh all devices (even not active ones)
    allDevices foreach { device =>
      client.updateDeviceState(device)
    }
    Future.successful(activeDevices.size) // to match MobilePushNotifier notifyUser API
  }

  // see https://docs.google.com/a/kifi.com/document/d/1efEGk8Wdj2dAjWjUWvsHW5UC0p2srjIXiju8tLpuOMU/edit# for spec
  private def jsonMessageExtra(notification: PushNotification, deviceType: DeviceType) = {
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
          case UserPushNotificationCategory.NewLibraryFollower => "nf"
          case _ => "us"
        }
        json.as[JsObject] ++ Json.obj("t" -> pushType, "uid" -> upn.userExtId, "un" -> upn.username.value, "purl" -> upn.pictureUrl)
      case _ =>
        throw new Exception(s"Don't recognize push notification $notification")
    }
  }

  //see http://docs.urbanairship.com/reference/api/v3/push.html
  private[realtime] def createAndroidJson(notification: PushNotification, device: Device) = {
    val audienceKey = if (device.isChannel) "android_channel" else "apid"
    notification.message.map { message =>
      Json.obj(
        "audience" -> Json.obj(audienceKey -> device.token),
        "device_types" -> Json.arr("android"),
        "notification" -> Json.obj(
          "android" -> Json.obj(
            "alert" -> message,
            "extra" -> jsonMessageExtra(notification, device.deviceType)
          )
        )
      )
    } getOrElse {
      Json.obj(
        "audience" -> Json.obj(audienceKey -> device.token),
        "device_types" -> Json.arr("android"),
        "notification" -> Json.obj(
          "android" -> Json.obj(
            "extra" -> jsonMessageExtra(notification, device.deviceType)
          )
        )
      )
    }
  }

  //see http://docs.urbanairship.com/reference/api/v3/push.html
  private[realtime] def createIosJson(notification: PushNotification, device: Device) = {
    val audienceKey = if (device.isChannel) "ios_channel" else "device_token"
    notification.message.map { message =>
      val ios = {
        val json = Json.obj(
          "alert" -> message.abbreviate(1000), //can be replaced with a json https://developer.apple.com/library/mac/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/ApplePushService.html#//apple_ref/doc/uid/TP40008194-CH100-SW9
          "badge" -> notification.unvisitedCount,
          "content-available" -> true,
          "extra" -> jsonMessageExtra(notification, device.deviceType)
        )
        notification.sound match {
          case Some(fileName) => json + ("sound" -> JsString(fileName.name))
          case None => json
        }
      }
      Json.obj(
        "audience" -> Json.obj(audienceKey -> device.token),
        "device_types" -> Json.arr("ios"),
        "notification" -> Json.obj(
          "ios" -> ios
        )
      )
    } getOrElse {
      Json.obj(
        "audience" -> Json.obj(audienceKey -> device.token),
        "device_types" -> Json.arr("ios"),
        "notification" -> Json.obj(
          "ios" -> Json.obj(
            "badge" -> notification.unvisitedCount,
            "content-available" -> false,
            "extra" -> jsonMessageExtra(notification, device.deviceType)
          )
        )
      )
    }
  }

  private def dealWithFailNotification(device: Device, notification: PushNotification, trial: Int, throwable: Throwable): Unit = {
    val (retry, retryText) = trial match {
      case 1 =>
        (Some(5 seconds), "retry in five seconds")
      case 2 =>
        (Some(1 minute), "retry in five seconds")
      case 3 =>
        (None, s"stop retries")
      case _ =>
        throw new Exception(s"WOW, how did I get to trial #$trial for device $device notification $notification)?!?")
    }
    airbrake.notify(s"fail to send a push notification $notification for device $device, $retryText: $throwable")
    retry foreach { timeout =>
      scheduler.scheduleOnce(timeout) {
        sendNotification(device, notification, trial + 1)
      }
    }
  }

  def sendNotification(device: Device, notification: PushNotification, trial: Int = 3): Unit = {
    val json = device.deviceType match {
      case DeviceType.IOS => createIosJson(notification, device)
      case DeviceType.Android => createAndroidJson(notification, device)
    }

    notification match {
      case spn: SimplePushNotification =>
        log.info(s"[UrbanAirship] Sending SimplePushNotification to user ${device.userId} device [${device.token}] with: $json")
      case mtpn: MessageThreadPushNotification =>
        log.info(s"[UrbanAirship] Sending MessageThreadPushNotification to user ${device.userId} device: [${device.token}] message ${mtpn.id}")
      case lupn: LibraryUpdatePushNotification =>
        log.info(s"[UrbanAirship] Sending LibraryUpdatePushNotification to user ${device.userId} device: [${device.token}] library ${lupn.libraryId} message ${lupn.message}")
      case upn: UserPushNotification =>
        log.info(s"[UrbanAirship] Sending UserPushNotification to user ${device.userId} device: [${device.token}] ${upn.username}:${upn.userExtId} message ${upn.message}")

    }

    client.send(json, device, notification) andThen {
      case Success(res) =>
        if (res.status / 100 != 2) {
          dealWithFailNotification(device, notification, trial, new Exception(s"bad status ${res.status} on push notification $notification for device $device response: ${res.body}"))
        } else {
          log.info(s"[UrbanAirship] successful send of push notification on trial $trial for device $deviceRepo: ${res.body}")
          messagingAnalytics.sentPushNotification(device, notification)
        }
      case Failure(e) =>
        dealWithFailNotification(device, notification, trial, new Exception(s"fail on push notification $notification for device $device", e))
    }
  }

}
