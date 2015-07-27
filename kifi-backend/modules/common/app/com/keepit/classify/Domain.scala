package com.keepit.classify

import java.security.MessageDigest

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

case class Domain(
    id: Option[Id[Domain]] = None,
    hostname: String,
    autoSensitive: Option[Boolean] = None,
    manualSensitive: Option[Boolean] = None,
    isEmailProvider: Boolean = false,
    hash: Option[DomainHash],
    state: State[Domain] = DomainStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[Domain] {
  require(this.hostname.toLowerCase == this.hostname, "Domain.hostname must be lowercase")
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
    (__ \ 'hostname).format[String] and
    (__ \ 'autoSensitive).formatNullable[Boolean] and
    (__ \ 'manualSensitive).formatNullable[Boolean] and
    (__ \ 'isEmailProvider).format[Boolean] and
    (__ \ 'hash).format[Option[DomainHash]] and
    (__ \ 'state).format(State.format[Domain]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime]
  )(Domain.apply, unlift(Domain.unapply))

  private val DomainRegex = """^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]+$""".r
  private val MaxLength = 256

  def isValid(s: String): Boolean = DomainRegex.findFirstIn(s).isDefined && s.length <= MaxLength

  def withHostname(hostname: String): Domain = Domain(hostname = hostname.toLowerCase, hash = Some(DomainHash.hashHostname(hostname.toLowerCase)))
}

case class DomainHash(hash: String) extends AnyVal {
  override def toString: String = hash
  def urlEncoded: String = hash.replaceAllLiterally("+" -> "-", "/" -> "_") // See RFC 3548 http://tools.ietf.org/html/rfc3548#page-6
}

object DomainHash {
  def hashHostname(hostname: String): DomainHash = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(hostname)
    DomainHash(new String(new Base64().encode(binaryHash), UTF8))
  }

  implicit val format: Format[DomainHash] = new Format[DomainHash] {
    def reads(json: JsValue): JsResult[DomainHash] = json.validate[String].map(DomainHash.apply)
    def writes(o: DomainHash): JsValue = JsString(o.hash)
  }
}

@json
case class DomainInfo(
  id: Option[Id[Domain]],
  hostname: String,
  isEmailProvider: Boolean)

object DomainStates extends States[Domain]

case class DomainKey(hostname: String) extends Key[Domain] {
  override val version = 3
  val namespace = "domain_by_hostname"
  def toKey(): String = hostname
}

case class DomainHashKey(domainHash: DomainHash) extends Key[Domain] {
  override val version = 1
  val namespace = "domain_by_hash"
  def toKey(): String = domainHash.hash
}

class DomainCache(stats: CacheStatistics, accessLog: AccessLog,
  innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[DomainKey, Domain](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class DomainHashCache(stats: CacheStatistics, accessLog: AccessLog,
  innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[DomainHashKey, Domain](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
