package com.keepit.realtime

import com.keepit.common.db.{ ExternalId, States, ModelWithState, State, Id }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime

case class Device(
    id: Option[Id[Device]] = None,
    userId: Id[User],
    token: Option[String],
    deviceType: DeviceType,
    state: State[Device] = DeviceStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    isDev: Boolean = false,
    signature: Option[String] = None) extends ModelWithState[Device] {

  def isChannel: Boolean = token.isDefined && ExternalId.UUIDPattern.pattern.matcher(token.get).matches()
  def withId(id: Id[Device]): Device = copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): Device = copy(updatedAt = updateTime)

  override def toString(): String = s"Device[id:$id,user:$userId,type:$deviceType,dev:$isDev]"
}

object DeviceStates extends States[Device]

sealed abstract class DeviceType(val name: String) {
  override def toString: String = name
}

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

