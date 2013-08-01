package com.keepit.realtime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.slick.driver.BasicProfile
import scala.slick.lifted.BaseTypeMapper

import org.joda.time.{Days, DateTime}

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.net.HttpClient
import com.keepit.common.time._
import com.keepit.model.{UserNotificationCategories, UserNotification, User}

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
case class PushNotification(id: ExternalId[UserNotification], unvisitedCount: Int, message: String)

object PushNotification {
  val MaxLength = 80
  private implicit val messageDetailsFormat = Json.format[MessageDetails]
  def fromUserNotification(notification: UserNotification, unvisitedCount: => Int): Option[PushNotification] = {
    notification.category match {
      case UserNotificationCategories.MESSAGE =>
        val details = Json.fromJson[MessageDetails](notification.details.payload).get
        val senderName = details.authors(0).firstName
        val text = if (details.text.size > MaxLength) s"${details.text.take(MaxLength - 3)}..." else details.text
        val message = s"$senderName: $text"
        Some(PushNotification(notification.externalId, unvisitedCount, message))
      case _ =>
        None
    }
  }
}

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
) extends UrbanAirship {

  lazy val authenticatedClient: HttpClient = {
    val encodedUserPass = new sun.misc.BASE64Encoder().encode(s"${config.key}:${config.secret}".getBytes)
    client.withHeaders("Authorization" -> s"Basic $encodedUserPass")
  }

  def registerDevice(userId: Id[User], token: String, deviceType: DeviceType): Device =
    db.readWrite { implicit s =>
      deviceRepo.get(userId, token, deviceType) match {
        case Some(d) if d.state == DeviceStates.ACTIVE => d
        case Some(d) => d.copy(state = DeviceStates.ACTIVE)
        case None => deviceRepo.save(Device(userId = userId, token = token, deviceType = deviceType))
      }
    }

  def notifyUser(userId: Id[User], notification: PushNotification): Unit = {
    for {
      deviceOpt <- db.readOnly { implicit s => deviceRepo.getByUserId(userId) }
      device <- updateDeviceState(deviceOpt) if device.state == DeviceStates.ACTIVE
    } {
      sendNotification(device, notification)
    }
  }

  def updateDeviceState(device: Device): Future[Device] = {
    if (device.updatedAt plus UrbanAirship.RecheckPeriod isBefore clock.now()) {
      authenticatedClient.getFuture(s"${config.baseUrl}/api/device_tokens/${device.token}") map { r =>
        val active = (r.json \ "active").as[Boolean]
        db.readWrite { implicit s =>
          deviceRepo.save(device.copy(state = if (active) DeviceStates.ACTIVE else DeviceStates.INACTIVE))
        }
      }
    } else Future.successful(device)
  }

  def sendNotification(device: Device, notification: PushNotification): Unit = device.deviceType match {
    case DeviceType.IOS =>
      authenticatedClient.postFuture(s"${config.baseUrl}/api/push", Json.obj(
        "device_tokens" -> Seq(device.token),
        "aps" -> Json.obj(
          "badge" -> notification.unvisitedCount,
          "alert" -> notification.message,
          "sound" -> UrbanAirship.NotificationSound
        ),
        "id" -> notification.id.id
      ))
    case DeviceType.Android =>
      ???
  }
}
