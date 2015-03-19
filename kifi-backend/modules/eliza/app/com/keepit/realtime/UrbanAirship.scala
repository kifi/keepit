package com.keepit.realtime

import com.keepit.eliza.{ PushNotificationExperiment, PushNotificationCategory }

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import com.google.common.cache.{ CacheLoader, CacheBuilder, LoadingCache }
import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.eliza.model._
import com.keepit.model.User
import org.joda.time.Days
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import scala.collection
import scala.concurrent.future

case class UrbanAirshipConfig(key: String, secret: String, devKey: String, devSecret: String, baseUrl: String = "https://go.urbanairship.com")

// Add fields to this object and handle them properly for each platform
sealed trait PushNotification {
  val unvisitedCount: Int
  val message: Option[String]
  val sound: Option[NotificationSound]
}

case class MessageThreadPushNotification(id: ExternalId[MessageThread], unvisitedCount: Int, message: Option[String], sound: Option[NotificationSound]) extends PushNotification
case class SimplePushNotification(unvisitedCount: Int, message: Option[String], sound: Option[NotificationSound] = None, category: PushNotificationCategory, experiment: PushNotificationExperiment) extends PushNotification

case class NotificationSound(name: String) extends AnyVal

object UrbanAirship {
  val DefaultNotificationSound = NotificationSound("notification.aiff")
  val MoreMessageNotificationSound = NotificationSound("newnotificationoutsidemessage.aiff")
  val RecheckPeriod = Days.THREE
}

@ImplementedBy(classOf[UrbanAirshipImpl])
trait UrbanAirship {
  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType, isDev: Boolean, signature: Option[String]): Device
  def notifyUser(userId: Id[User], notification: PushNotification): Int
  def sendNotification(device: Device, notification: PushNotification): Unit
}

@Singleton
class UrbanAirshipImpl @Inject() (
    client: UrbanAirshipClient,
    deviceRepo: DeviceRepo,
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    scheduler: Scheduler) extends UrbanAirship with Logging {

  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType, isDev: Boolean, signatureOpt: Option[String]): Device = synchronized {
    log.info(s"Registering device: $deviceType:$token for (user $userId, signature $signatureOpt)")

    val device = if (signatureOpt.isDefined) {
      val signature = signatureOpt.get

      // find all devices for user with deviceType, but no signature and deactivate them!
      db.readWrite { implicit s =>
        val noSignatureDevices = deviceRepo.getByUserIdAndDeviceType(userId, deviceType).filter(_.signature.isEmpty)
        noSignatureDevices.map { d =>
          deviceRepo.save(d.copy(state = DeviceStates.INACTIVE))
        }
      }

      db.readOnlyMaster { implicit s =>
        deviceRepo.getByUserIdAndDeviceTypeAndSignature(userId, deviceType, signature, None)
      } match {
        case Some(d) => // update or reactivate an existing device
          db.readWrite { implicit s =>
            deviceRepo.save(d.copy(token = token, isDev = isDev, state = DeviceStates.ACTIVE))
          }
        case None => // new device for user! save new device!
          db.readWrite { implicit s =>
            deviceRepo.save(Device(userId = userId, token = token, deviceType = deviceType, isDev = isDev, signature = signatureOpt))
          }
      }

    } else { // no signature provided... can only deal with tokens (old logic)
      db.readWrite { implicit s =>
        // deactivate all devices with token & deviceType but don't match current userId and don't have signature
        deviceRepo.get(token, deviceType).filter(d => d.userId != userId && d.signature.isEmpty).map { d =>
          deviceRepo.save(d.copy(state = DeviceStates.INACTIVE))
        }
        // find device with token & device type
        deviceRepo.get(userId, token, deviceType) match {
          case Some(d) if d.state == DeviceStates.ACTIVE && d.isDev == isDev => d
          case Some(d) => deviceRepo.save(d.copy(state = DeviceStates.ACTIVE, isDev = isDev))
          case None => deviceRepo.save(Device(userId = userId, token = token, deviceType = deviceType, isDev = isDev))
        }
      }
    }
    future {
      val devices = db.readOnlyReplica { implicit s => deviceRepo.getByUserId(userId) }
      devices foreach client.updateDeviceState
    }
    device
  }

  def getDevices(userId: Id[User]): Seq[Device] = {
    val devices = db.readOnlyMaster { implicit s =>
      deviceRepo.getByUserId(userId).groupBy(_.deviceType)
    }
    val onePerType = devices map {
      case (deviceType, devicesOfType) => deviceType -> devicesOfType.sortBy(_.updatedAt).reverse.head
    }
    onePerType.values.toSeq
  }

  def notifyUser(userId: Id[User], notification: PushNotification): Int = {
    val devices: Seq[Device] = getDevices(userId)
    log.info(s"Notifying user: $userId with $devices")
    //get only active devices
    val activeDevices = devices filter { d =>
      d.state == DeviceStates.ACTIVE
    }
    //send them all a push notification
    activeDevices foreach { device =>
      sendNotification(device, notification)
    }
    log.info(s"user $userId has ${activeDevices.size} active devices out of ${devices.size} for notification $notification")
    //refresh all devices (even not active ones)
    devices foreach { device =>
      client.updateDeviceState(device)
    }
    activeDevices.size
  }

  private def jsonMessageExtra(notification: PushNotification) = {
    val json = Json.obj("unreadCount" -> notification.unvisitedCount)
    notification match {
      case spn: SimplePushNotification => json
      case mtpn: MessageThreadPushNotification => json.as[JsObject] + ("id" -> JsString(mtpn.id.id))
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
            "extra" -> jsonMessageExtra(notification)
          )
        )
      )
    } getOrElse {
      Json.obj(
        "audience" -> Json.obj(audienceKey -> device.token),
        "device_types" -> Json.arr("android"),
        "notification" -> Json.obj(
          "android" -> Json.obj(
            "extra" -> jsonMessageExtra(notification)
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
          "extra" -> jsonMessageExtra(notification)
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
            "extra" -> jsonMessageExtra(notification)
          )
        )
      )
    }
  }

  def sendNotification(device: Device, notification: PushNotification): Unit = {
    log.info(s"Sending notification to device: ${device.token}")

    val json = device.deviceType match {
      case DeviceType.IOS => createIosJson(notification, device)
      case DeviceType.Android => createAndroidJson(notification, device)
    }
    client.send(json, device, notification).onFailure {
      case e1 =>
        log.error(s"fail to send a push notification $notification for device $device, retry in five seconds", e1)
        scheduler.scheduleOnce(5 seconds) {
          client.send(json, device, notification).onFailure {
            case e2 =>
              log.error(s"fail to send a push notification $notification for device $device, second retry in one minute", e2)
              scheduler.scheduleOnce(1 minute) {
                client.send(json, device, notification).onFailure {
                  case e3 =>
                    airbrake.notify(s"fail to send a push notification $notification for device $device, after two retries: $e3")
                }
              }
          }
        }
    }
  }

}
