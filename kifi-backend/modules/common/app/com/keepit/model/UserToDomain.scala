package com.keepit.model

import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.logging.AccessLog
import org.joda.time.DateTime

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.time._
import com.keepit.classify.Domain
import play.api.libs.functional.syntax._

import play.api.libs.json._

import scala.concurrent.duration.Duration

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

object UserToDomainKind {
  implicit val format = Json.format[UserToDomainKind]
}

object UserToDomain {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[UserToDomain]) and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'domainId).format[Id[Domain]] and
    (__ \ 'kind).format[UserToDomainKind] and
    (__ \ 'value).formatNullable[JsValue] and
    (__ \ 'state).format(State.format[UserToDomain]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime]
  )(UserToDomain.apply, unlift(UserToDomain.unapply))
}

object UserToDomainKinds {
  val NEVER_SHOW = UserToDomainKind("never_show")
  val KEEPER_POSITION = UserToDomainKind("keeper_position")

  def apply(str: String): UserToDomainKind = str.toLowerCase.trim match {
    case NEVER_SHOW.value => NEVER_SHOW
  }
}

object UserToDomainStates extends States[UserToDomain]

case class UserToDomainKey(userId: Id[User], domainId: Id[Domain], kind: UserToDomainKind) extends Key[Option[UserToDomain]] {
  override val version = 1
  val namespace = "user_to_domain"
  def toKey(): String = s"$userId:$domainId:$kind"
}

class UserToDomainCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[UserToDomainKey, Option[UserToDomain]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

