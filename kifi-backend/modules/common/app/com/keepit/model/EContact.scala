package com.keepit.model

import com.keepit.common.time._
import com.keepit.common.db._
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.mail.EmailAddress

object EContactStates extends States[EContact] {
  val PARSE_FAILURE = State[EContact]("parse_failure")
}

case class EContact(
  id: Option[Id[EContact]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId:    Id[User],
  email:     EmailAddress,
  name:      Option[String] = None,
  firstName: Option[String] = None,
  lastName:  Option[String] = None,
  contactUserId: Option[Id[User]] = None,
  state:     State[EContact] = EContactStates.ACTIVE
) extends ModelWithState[EContact] {
  def withId(id: Id[EContact]) = this.copy(id = Some(id))
  def withName(name: Option[String]) = this.copy(name = name)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object EContact {
  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[EContact]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'userId).format(Id.format[User]) and
      (__ \ 'email).format[EmailAddress] and
      (__ \ 'name).formatNullable[String] and
      (__ \ 'firstName).formatNullable[String] and
      (__ \ 'lastName).formatNullable[String] and
      (__ \ 'contactUserId).formatNullable(Id.format[User]) and
      (__ \ 'state).format(State.format[EContact])
    )(EContact.apply, unlift(EContact.unapply))
}

class EContactCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[EContactKey, EContact](stats, accessLog, inner, outer: _*)

case class EContactKey(id: Id[EContact]) extends Key[EContact] {
  val namespace = "econtact"
  override val version = 1
  def toKey(): String = id.id.toString
}