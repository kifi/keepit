package com.keepit.realtime

import java.util.concurrent.TimeoutException

import scala.concurrent.{ Future, future }

import org.joda.time.{ Days, DateTime }

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.net.{ NonOKResponseException, HttpClient, DirectUrl }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.common.logging.Logging
import com.keepit.eliza.model._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json

case class UrbanAirshipConfig(key: String, secret: String, baseUrl: String = "https://go.urbanairship.com")

case class Device(
    id: Option[Id[Device]] = None,
    userId: Id[User],
    token: String,
    deviceType: DeviceType,
    state: State[Device] = DeviceStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[Device] {

  def withId(id: Id[Device]): Device = copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): Device = copy(updatedAt = updateTime)
}

object DeviceStates extends States[Device]

@ImplementedBy(classOf[DeviceRepoImpl])
trait DeviceRepo extends Repo[Device] {
  def getByUserId(userId: Id[User], excludeState: Option[State[Device]] = Some(DeviceStates.INACTIVE))(implicit s: RSession): Seq[Device]
  def get(userId: Id[User], token: String, deviceType: DeviceType)(implicit s: RSession): Option[Device]
  def get(token: String, deviceType: DeviceType)(implicit s: RSession): Seq[Device]
}

class DeviceRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DeviceRepo with DbRepo[Device] with MessagingTypeMappers {

  import db.Driver.simple._
  implicit val deviceTypeTypeMapper = MappedColumnType.base[DeviceType, String](_.name, DeviceType.apply)

  type RepoImpl = DeviceTable
  class DeviceTable(tag: Tag) extends RepoTable[Device](db, tag, "device") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def token = column[String]("token", O.NotNull)
    def deviceType = column[DeviceType]("device_type", O.NotNull)
    def * = (id.?, userId, token, deviceType, state, createdAt, updatedAt) <> ((Device.apply _).tupled, Device.unapply _)
  }
  def table(tag: Tag) = new DeviceTable(tag)

  override def deleteCache(model: Device)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: Device)(implicit session: RSession): Unit = {}

  def getByUserId(userId: Id[User], excludeState: Option[State[Device]])(implicit s: RSession): Seq[Device] = {
    (for (t <- rows if t.userId === userId && t.state =!= excludeState.orNull) yield t).list
  }

  def get(userId: Id[User], token: String, deviceType: DeviceType)(implicit s: RSession): Option[Device] = {
    (for (t <- rows if t.userId === userId && t.token === token && t.deviceType === deviceType) yield t).firstOption
  }

  def get(token: String, deviceType: DeviceType)(implicit s: RSession): Seq[Device] = {
    (for (t <- rows if t.token === token && t.deviceType === deviceType) yield t).list
  }
}

sealed abstract class DeviceType(val name: String)

object DeviceType {
  case object Android extends DeviceType("android")
  case object IOS extends DeviceType("ios")
  val AllTypes = Set(Android, IOS)
  def apply(s: String): DeviceType = {
    AllTypes.find(_.name equalsIgnoreCase s.trim)
      .getOrElse(throw new IllegalArgumentException("invalid device type string"))
  }
  def unapply(dt: DeviceType): Option[String] = Some(dt.name)
}

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
  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType): Device
  def notifyUser(userId: Id[User], notification: PushNotification): Unit
  def sendNotification(firstMessage: Boolean, device: Device, notification: PushNotification): Unit
  def updateDeviceState(device: Device): Future[Device]
}

class UrbanAirshipImpl @Inject() (
    client: HttpClient,
    config: UrbanAirshipConfig,
    deviceRepo: DeviceRepo,
    db: Database,
    clock: Clock) extends UrbanAirship with Logging {

  lazy val authenticatedClient: HttpClient = {
    val encodedUserPass = new sun.misc.BASE64Encoder().encode(s"${config.key}:${config.secret}".getBytes)
    client.withHeaders("Authorization" -> s"Basic $encodedUserPass")
  }

  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType): Device = synchronized {
    log.info(s"Registering device: $token (user $userId)")
    val device = db.readWrite { implicit s =>
      deviceRepo.get(token, deviceType).map { d =>
        if (d.userId != userId) deviceRepo.save(d.copy(state = DeviceStates.INACTIVE))
      }
      deviceRepo.get(userId, token, deviceType) match {
        case Some(d) if d.state == DeviceStates.ACTIVE => d
        case Some(d) => deviceRepo.save(d.copy(state = DeviceStates.ACTIVE))
        case None => deviceRepo.save(Device(userId = userId, token = token, deviceType = deviceType))
      }
    }
    future {
      val devices = db.readOnlyReplica { implicit s => deviceRepo.getByUserId(userId) }
      devices foreach updateDeviceState
    }
    device
  }

  def notifyUser(userId: Id[User], notification: PushNotification): Unit = {
    // todo: Check shoebox if user has a notification preference
    // UserNotifyPreferenceRepo.canSend(userId, someIdentifierRepresentingMobileNotificationType)
    log.info(s"Notifying user: $userId")
    for {
      d <- db.readOnlyReplica { implicit s => deviceRepo.getByUserId(userId) }
      device <- updateDeviceState(d) if device.state == DeviceStates.ACTIVE
    } {
      sendNotification(false, device, notification)
    }
  }

  def updateDeviceState(device: Device): Future[Device] = {
    log.info(s"Checking state of device: ${device.token}")
    if (device.updatedAt plus UrbanAirship.RecheckPeriod isBefore clock.now()) {
      val uaUrl = device.deviceType match {
        case DeviceType.IOS => s"${config.baseUrl}/api/device_tokens/${device.token}"
        case DeviceType.Android => s"${config.baseUrl}/api/apids/${device.token}"
        case dt => throw new Exception(s"Unknown device type: $dt")
      }
      authenticatedClient.getFuture(DirectUrl(uaUrl), url => {
        case e @ NonOKResponseException(url, response, _) if response.status == NOT_FOUND =>
      }) map { r =>
        val active = (r.json \ "active").as[Boolean]
        db.readWrite { implicit s =>
          val state = if (active) DeviceStates.ACTIVE else DeviceStates.INACTIVE
          log.info(s"Setting device state to $state: ${device.token}")
          deviceRepo.save(device.copy(state = state))
        }
      } recover {
        case e @ NonOKResponseException(url, response, _) if response.status == NOT_FOUND =>
          db.readWrite { implicit s =>
            log.info(s"Setting device state to inactive: ${device.token}")
            deviceRepo.save(device.copy(state = DeviceStates.INACTIVE))
          }
      }
    } else Future.successful(device)
  }

  private def postIOS(firstMessage: Boolean, device: Device, notification: PushNotification, retry: Boolean = false): Unit = {
    val json = notification.message.map { message =>
      Json.obj(
        "device_tokens" -> Seq(device.token),
        "aps" -> Json.obj(
          "badge" -> notification.unvisitedCount,
          "alert" -> message,
          "sound" -> notification.sound.get.name
        ),
        "id" -> notification.id.id
      )
    } getOrElse {
      Json.obj(
        "device_tokens" -> Seq(device.token),
        "aps" -> Json.obj("badge" -> notification.unvisitedCount),
        "id" -> notification.id.id
      )
    }
    authenticatedClient.postFuture(DirectUrl(s"${config.baseUrl}/api/push"), json,
      { req =>
        {
          case e: TimeoutException =>
            log.error(s"timeout error posting to urbanairship on device $device notification $notification, doing one more retry", e)
            if (retry) {
              authenticatedClient.defaultFailureHandler(req)
              throw new Exception(s"[second try] error posting to urbanairship on device $device notification $notification, not attempting more retries", e)
            }
            postIOS(firstMessage, device, notification, retry = true)
          case t: Throwable =>
            authenticatedClient.defaultFailureHandler(req)
            throw new Exception(s"error posting to urbanairship on device $device notification $notification, not attempting retries", t)
        }
      }
    )
  }

  private def postAndroid(firstMessage: Boolean, device: Device, notification: PushNotification, retry: Boolean = false): Unit = {
    val json = notification.message.map { message =>
      Json.obj(
        "apids" -> Seq(device.token),
        "android" -> Json.obj(
          "alert" -> message,
          "extra" -> Json.obj(
            "unreadCount" -> notification.unvisitedCount.toString,
            "id" -> notification.id.id
          )
        )
      )
    } getOrElse {
      Json.obj(
        "apids" -> Seq(device.token),
        "android" -> Json.obj(
          "alert" -> s"",
          "extra" -> Json.obj(
            "unreadCount" -> notification.unvisitedCount.toString,
            "id" -> notification.id.id
          )
        )
      )
    }
    authenticatedClient.postFuture(DirectUrl(s"${config.baseUrl}/api/push"), json,
      { req =>
        {
          case e: TimeoutException =>
            log.error(s"timeout error posting to urbanairship on device $device notification $notification, doing one more retry", e)
            if (retry) {
              authenticatedClient.defaultFailureHandler(req)
              throw new Exception(s"[second try] error posting to urbanairship on device $device notification $notification, not attempting more retries", e)
            }
            postAndroid(firstMessage, device, notification, retry = true)
          case t: Throwable =>
            authenticatedClient.defaultFailureHandler(req)
            throw new Exception(s"error posting to urbanairship on device $device notification $notification, not attempting retries", t)
        }
      }
    )
  }

  def sendNotification(firstMessage: Boolean, device: Device, notification: PushNotification): Unit = {
    log.info(s"Sending notification to device: ${device.token}")
    device.deviceType match {
      case DeviceType.IOS =>
        postIOS(firstMessage, device, notification)
      case DeviceType.Android =>
        postAndroid(firstMessage, device, notification)
    }
  }
}
