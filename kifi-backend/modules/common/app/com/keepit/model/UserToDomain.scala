package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.time._
import com.keepit.classify.Domain

import play.api.libs.json.JsValue

case class UserToDomain(
    id: Option[Id[UserToDomain]] = None,
    userId: Id[User],
    domainId: Id[Domain],
    kind: UserToDomainKind,
    value: Option[JsValue],
    state: State[UserToDomain] = UserToDomainStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[UserToDomain] {
  def withId(id: Id[UserToDomain]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserToDomain]) = this.copy(state = state)
  def withValue(value: Option[JsValue]) = this.copy(value = value)
  def isActive = state == UserToDomainStates.ACTIVE
}

sealed case class UserToDomainKind(val value: String)

object UserToDomainKinds {
  val NEVER_SHOW = UserToDomainKind("never_show")
  val KEEPER_POSITION = UserToDomainKind("keeper_position")

  def apply(str: String): UserToDomainKind = str.toLowerCase.trim match {
    case NEVER_SHOW.value => NEVER_SHOW
  }
}

object UserToDomainStates extends States[UserToDomain]
