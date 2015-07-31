package com.keepit.classify

import java.security.MessageDigest

import java.net.IDN
import com.keepit.common.strings._
import com.kifi.macros.json
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import com.keepit.common.db.{ State, States, ModelWithState, Id }
import com.keepit.common.time._
import com.keepit.common.logging.AccessLog
import com.keepit.model.{ Normalization }
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }

case class Domain(
    id: Option[Id[Domain]] = None,
    hostname: NormalizedHostname,
    autoSensitive: Option[Boolean] = None,
    manualSensitive: Option[Boolean] = None,
    isEmailProvider: Boolean = false,
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
  def toDomainInfo = DomainInfo(id, hostname, isEmailProvider)
}

object Domain {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[Domain]) and
    (__ \ 'hostname).format[NormalizedHostname] and
    (__ \ 'autoSensitive).formatNullable[Boolean] and
    (__ \ 'manualSensitive).formatNullable[Boolean] and
    (__ \ 'isEmailProvider).format[Boolean] and
    (__ \ 'state).format(State.format[Domain]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime]
  )(Domain.apply, unlift(Domain.unapply))

  def fromHostname(hostname: String) = Domain(hostname = NormalizedHostname.fromHostname(hostname))
}

@json
case class DomainInfo(
  id: Option[Id[Domain]],
  hostname: NormalizedHostname,
  isEmailProvider: Boolean)

case class NormalizedHostname(value: String) // use NormalizedHostname.fromHostname

object NormalizedHostname {
  private val DomainRegex = """^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]+$""".r
  private val MaxLength = 256
  def isValid(s: String): Boolean = DomainRegex.pattern.matcher(s).matches && s.length < MaxLength && Try(IDN.toASCII(s)).isSuccess

  def fromHostname(hostname: String) = NormalizedHostname(IDN.toASCII(hostname.toLowerCase))

  implicit def format: Format[NormalizedHostname] = new Format[NormalizedHostname] {
    def reads(json: JsValue) = {
      val trimmed = json.as[String].trim
      try {
        JsSuccess(NormalizedHostname.fromHostname(trimmed))
      } catch {
        case ex: Throwable => JsError(ex.getMessage)
      }
    }
    def writes(o: NormalizedHostname) = JsString(o.value)
  }
}

object DomainStates extends States[Domain]

case class DomainKey(hostname: NormalizedHostname) extends Key[Domain] {
  override val version = 3
  val namespace = "domain_by_hostname"
  def toKey(): String = hostname.value
}

class DomainCache(stats: CacheStatistics, accessLog: AccessLog,
  innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[DomainKey, Domain](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
