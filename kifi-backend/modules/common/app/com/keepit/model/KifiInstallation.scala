package com.keepit.model

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, Key, StringCacheImpl }
import com.keepit.common.db.{ ExternalId, Id, ModelWithExternalId, ModelWithState, State, States }
import com.keepit.common.logging.{ AccessLog }
import com.keepit.common.net.UserAgent
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.concurrent.duration.Duration

sealed trait KifiInstallationPlatform {
  def name: String
}

object KifiInstallationPlatform {

  sealed trait IPhonePlatform extends KifiInstallationPlatform
  sealed trait AndroidPlatform extends KifiInstallationPlatform
  sealed trait ExtPlatform extends KifiInstallationPlatform

  object Extension extends ExtPlatform { val name = "extension" }
  object IPhone extends IPhonePlatform { val name = "iphone" }
  object Android extends AndroidPlatform { val name = "android" }

  def apply(name: String): KifiInstallationPlatform = name.toLowerCase match {
    case Extension.name => Extension
    case IPhone.name => IPhone
    case Android.name => Android
  }
}

case class KifiInstallation(
    id: Option[Id[KifiInstallation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    externalId: ExternalId[KifiInstallation] = ExternalId(),
    version: KifiVersion,
    userAgent: UserAgent,
    platform: KifiInstallationPlatform,
    state: State[KifiInstallation] = KifiInstallationStates.ACTIVE) extends ModelWithExternalId[KifiInstallation] with ModelWithState[KifiInstallation] {

  version match {
    case KifiIPhoneVersion(_, _, _, _) => require(platform == KifiInstallationPlatform.IPhone)
    case KifiAndroidVersion(_, _, _, _) => require(platform == KifiInstallationPlatform.Android)
    case KifiExtVersion(_, _, _, _) => require(platform == KifiInstallationPlatform.Extension)
  }

  def withId(id: Id[KifiInstallation]): KifiInstallation = copy(id = Some(id))
  def withUpdateTime(time: DateTime): KifiInstallation = copy(updatedAt = time)
  def withVersion(version: KifiVersion): KifiInstallation = copy(version = version)
  def withUserAgent(userAgent: UserAgent): KifiInstallation = copy(userAgent = userAgent)
  def withState(state: State[KifiInstallation]): KifiInstallation = copy(state = state)
  def isActive: Boolean = state == KifiInstallationStates.ACTIVE
}

object KifiInstallationStates extends States[KifiInstallation]

case class ExtensionVersionInstallationIdKey(externalId: ExternalId[KifiInstallation]) extends Key[String] {
  override val version = 1
  val namespace = "extension_version_by_installation_id"
  def toKey(): String = externalId.id
}

class ExtensionVersionInstallationIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[ExtensionVersionInstallationIdKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
