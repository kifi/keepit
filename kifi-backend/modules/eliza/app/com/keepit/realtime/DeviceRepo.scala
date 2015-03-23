package com.keepit.realtime

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.time.Clock
import com.keepit.eliza.model.MessagingTypeMappers
import com.keepit.model.User

@ImplementedBy(classOf[DeviceRepoImpl])
trait DeviceRepo extends Repo[Device] {
  def getByUserIdAndDeviceTypeAndSignature(userId: Id[User], deviceType: DeviceType, signature: String, excludeState: Option[State[Device]] = Some(DeviceStates.INACTIVE))(implicit s: RSession): Option[Device]
  def getByUserId(userId: Id[User], excludeState: Option[State[Device]] = Some(DeviceStates.INACTIVE))(implicit s: RSession): Seq[Device]
  def getByUserIdAndDeviceType(userId: Id[User], deviceType: DeviceType, excludeState: Option[State[Device]] = Some(DeviceStates.INACTIVE))(implicit s: RSession): Seq[Device]
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
    def isDev = column[Boolean]("is_dev", O.NotNull)
    def signature = column[String]("signature", O.Nullable)
    def * = (id.?, userId, token, deviceType, state, createdAt, updatedAt, isDev, signature.?) <> ((Device.apply _).tupled, Device.unapply _)
  }

  def table(tag: Tag) = new DeviceTable(tag)

  override def deleteCache(model: Device)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: Device)(implicit session: RSession): Unit = {}

  def getByUserId(userId: Id[User], excludeState: Option[State[Device]])(implicit s: RSession): Seq[Device] = {
    (for (t <- rows if t.userId === userId && t.state =!= excludeState.orNull) yield t).list
  }

  def getByUserIdAndDeviceTypeAndSignature(userId: Id[User], deviceType: DeviceType, signature: String, excludeState: Option[State[Device]] = Some(DeviceStates.INACTIVE))(implicit s: RSession): Option[Device] = {
    (for (t <- rows if t.userId === userId && t.deviceType === deviceType && t.signature === signature && t.state =!= excludeState.orNull) yield t).firstOption
  }

  def getByUserIdAndDeviceType(userId: Id[User], deviceType: DeviceType, excludeState: Option[State[Device]] = Some(DeviceStates.INACTIVE))(implicit s: RSession): Seq[Device] = {
    (for (t <- rows if t.userId === userId && t.deviceType === deviceType && t.state =!= excludeState.orNull) yield t).list
  }

  def get(userId: Id[User], token: String, deviceType: DeviceType)(implicit s: RSession): Option[Device] = {
    (for (t <- rows if t.userId === userId && t.token === token && t.deviceType === deviceType) yield t).firstOption
  }

  def get(token: String, deviceType: DeviceType)(implicit s: RSession): Seq[Device] = {
    (for (t <- rows if t.token === token && t.deviceType === deviceType) yield t).list
  }
}

