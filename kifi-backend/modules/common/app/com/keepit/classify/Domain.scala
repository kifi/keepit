package com.keepit.classify

import org.joda.time.DateTime
import com.keepit.common.db.{ State, States, ModelWithState, Id }
import com.keepit.common.time._
import com.keepit.common.logging.AccessLog
import com.keepit.model.Normalization
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import scala.concurrent.duration.Duration

case class Domain(
    id: Option[Id[Domain]] = None,
    hostname: String,
    autoSensitive: Option[Boolean] = None,
    manualSensitive: Option[Boolean] = None,
    state: State[Domain] = DomainStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[Domain] {
  def withId(id: Id[Domain]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withAutoSensitive(sensitive: Option[Boolean]) = this.copy(autoSensitive = sensitive)
  def withManualSensitive(sensitive: Option[Boolean]) = this.copy(manualSensitive = sensitive)
  def withState(state: State[Domain]) = this.copy(state = state)
  val sensitive: Option[Boolean] = manualSensitive orElse autoSensitive
  def isActive: Boolean = state == DomainStates.ACTIVE
}

object Domain {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[Domain]) and
    (__ \ 'hostname).format[String] and
    (__ \ 'autoSensitive).formatNullable[Boolean] and
    (__ \ 'manualSensitive).formatNullable[Boolean] and
    (__ \ 'state).format(State.format[Domain]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime]
  )(Domain.apply, unlift(Domain.unapply))

  private val DomainRegex = """^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]+$""".r
  private val MaxLength = 128

  def isValid(s: String): Boolean = DomainRegex.findFirstIn(s).isDefined && s.length <= MaxLength
}

object DomainStates extends States[Domain]

case class DomainKey(hostname: String) extends Key[Domain] {
  override val version = 2
  val namespace = "domain_by_hostname"
  def toKey(): String = hostname
}

class DomainCache(stats: CacheStatistics, accessLog: AccessLog,
  innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[DomainKey, Domain](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
