package com.keepit.realtime

import java.util.concurrent.TimeoutException

import scala.concurrent.{Future, future, Promise}
import scala.slick.driver.BasicProfile
import scala.slick.lifted.BaseTypeMapper

import org.joda.time.{Days, DateTime}

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.net.{NonOKResponseException, HttpClient, DirectUrl}
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.common.logging.Logging
import com.keepit.eliza.{MessageThread, Message}

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
                   updatedAt: DateTime = currentDateTime
                   ) extends Model[Device] {
  def withId(id: Id[Device]): Device = copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): Device = copy(updatedAt = updateTime)
}

object DeviceStates extends States[Device]

@ImplementedBy(classOf[DeviceRepoImpl])
trait DeviceRepo extends Repo[Device] {
  def getByUserId(userId: Id[User], excludeState: Option[State[Device]] = Some(DeviceStates.INACTIVE))
                 (implicit s: RSession): Seq[Device]
  def get(userId: Id[User], token: String, deviceType: DeviceType)(implicit s: RSession): Option[Device]
}

class DeviceRepoImpl @Inject()(val db: DataBaseComponent, val clock: Clock) extends DeviceRepo with DbRepo[Device] {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  implicit object DeviceIdTypeMapper extends BaseTypeMapper[Id[Device]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[Device](profile)
  }
  implicit object DeviceTypeTypeMapper extends BaseTypeMapper[DeviceType] {
    def apply(profile: BasicProfile) = new StringMapperDelegate[DeviceType](profile) {
      def sourceToDest(dest: DeviceType) = dest.name
      def safeDestToSource(source: String) = DeviceType(source)
      def zero = DeviceType.IOS
    }
  }

  override val table = new RepoTable[Device](db, "device") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def token = column[String]("token", O.NotNull)
    def deviceType = column[DeviceType]("device_type", O.NotNull)
    def * = id.? ~ userId ~ token ~ deviceType ~ state ~ createdAt ~ updatedAt <> (Device.apply _, Device.unapply _)
  }

  def getByUserId(userId: Id[User], excludeState: Option[State[Device]])(implicit s: RSession): Seq[Device] = {
    (for (t <- table if t.userId === userId && t.state =!= excludeState.orNull) yield t).list
  }

  def get(userId: Id[User], token: String, deviceType: DeviceType)(implicit s: RSession): Option[Device] = {
    (for (t <- table if t.userId === userId && t.token === token && t.deviceType === deviceType) yield t).firstOption
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
case class PushNotification(id: ExternalId[MessageThread], unvisitedCount: Int, message: String)

object UrbanAirship {
  val NotificationSound = "notification.aiff"
  val RecheckPeriod = Days.ONE
}

@ImplementedBy(classOf[UrbanAirshipImpl])
trait UrbanAirship {
  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType): Device
  def notifyUser(userId: Id[User], notification: PushNotification): Unit
  def sendNotification(device: Device, notification: PushNotification): Unit
  def updateDeviceState(device: Device): Future[Device]
}

class UrbanAirshipImpl @Inject()(
                                  client: HttpClient,
                                  config: UrbanAirshipConfig,
                                  deviceRepo: DeviceRepo,
                                  db: Database,
                                  clock: Clock
                                  ) extends UrbanAirship with Logging {

  lazy val authenticatedClient: HttpClient = {
    val encodedUserPass = new sun.misc.BASE64Encoder().encode(s"${config.key}:${config.secret}".getBytes)
    client.withHeaders("Authorization" -> s"Basic $encodedUserPass")
  }

  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType): Device = {
    log.info(s"Registering device: $token (user $userId)")
    val device = db.readWrite { implicit s =>
      deviceRepo.get(userId, token, deviceType) match {
        case Some(d) if d.state == DeviceStates.ACTIVE => d
        case Some(d) => deviceRepo.save(d.copy(state = DeviceStates.ACTIVE))
        case None => deviceRepo.save(Device(userId = userId, token = token, deviceType = deviceType))
      }
    }
    future {
      val devices = db.readOnly { implicit s => deviceRepo.getByUserId(userId) }
      devices.foreach{ updateDeviceState(_) }
    }
    device
  }

  def notifyUser(userId: Id[User], notification: PushNotification): Unit = {
    // todo: Check shoebox if user has a notification preference
    // UserNotifyPreferenceRepo.canSend(userId, someIdentifierRepresentingMobileNotificationType)
    log.info(s"Notifying user: $userId")
    for {
      d <- db.readOnly { implicit s => deviceRepo.getByUserId(userId) }
      device <- updateDeviceState(d) if device.state == DeviceStates.ACTIVE
    } {
      sendNotification(device, notification)
    }
  }

  def updateDeviceState(device: Device): Future[Device] = {
    log.info(s"Checking state of device: ${device.token}")
    if (device.updatedAt plus UrbanAirship.RecheckPeriod isBefore clock.now()) {
      authenticatedClient.getFuture(DirectUrl(s"${config.baseUrl}/api/device_tokens/${device.token}"), url => {
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

  private def postIOS(device: Device, notification: PushNotification): Unit = authenticatedClient.postFuture(
    DirectUrl(s"${config.baseUrl}/api/push"), Json.obj(
      "device_tokens" -> Seq(device.token),
      "aps" -> Json.obj(
        "badge" -> notification.unvisitedCount,
        "alert" -> notification.message,
        "sound" -> UrbanAirship.NotificationSound
      ),
      "id" -> notification.id.id
    )
  )

  def sendNotification(device: Device, notification: PushNotification): Unit = {
    log.info(s"Sending notification to device: ${device.token}")
    device.deviceType match {
      case DeviceType.IOS =>
        try {
          postIOS(device, notification)
        } catch {
          case e: TimeoutException =>
            log.error(s"timeout error posting to urbanairship on device $device notification $notification, doing one more retry", e)
            try {
              postIOS(device, notification)
            } catch {
              case t: Throwable =>
                log.error(s"[second try] error posting to urbanairship on device $device notification $notification, not attempting more retries", t)
            }
          case t: Throwable =>
            throw new Exception(s"error posting to urbanairship on device $device notification $notification, not attempting retries", t)
        }
      case DeviceType.Android =>
        ???
    }
  }
}

class FakeUrbanAirshipImpl extends UrbanAirship {
  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType): Device = ???
  def notifyUser(userId: Id[User], notification: PushNotification): Unit = {}
  def sendNotification(device: Device, notification: PushNotification): Unit = {}
  def updateDeviceState(device: Device): Future[Device] = Promise.successful(device).future
}
