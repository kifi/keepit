package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.net.UserAgent
import org.joda.time.DateTime
import com.keepit.common.logging.{AccessLog, Logging}
import play.api.mvc.QueryStringBindable
import play.api.mvc.JavascriptLitteral
import com.keepit.common.cache._
import scala.concurrent.duration.Duration

sealed trait KifiInstallationPlatform {
  def name: String
}

object KifiInstallationPlatform {

  sealed trait IPhonePlatform extends KifiInstallationPlatform
  sealed trait ExtPlatform extends KifiInstallationPlatform

  object Extension extends ExtPlatform { val name = "extension" }
  object IPhone extends IPhonePlatform { val name = "iphone" }

  def apply(name: String): KifiInstallationPlatform = name.toLowerCase match {
    case Extension.name => Extension
    case IPhone.name => IPhone
  }
}

trait KifiVersion {

  def major: Int
  def minor: Int
  def patch: Int
  def tag: String

  assert(major >= 0 && minor >= 0 && patch >= 0)

  def compareIt(that: KifiVersion) =
    ((this.major - that.major) << 20) +
      ((this.minor - that.minor) << 10) +
      (this.patch - that.patch)

  override def toString = {
    Seq(major, minor, patch).mkString(".") + (if(tag != "") "-" + tag else "")
  }
}

case class KifiIPhoneVersion(major: Int, minor: Int, patch: Int, tag: String = "") extends KifiVersion with Ordered[KifiIPhoneVersion] {
  def compare(that: KifiIPhoneVersion) = compareIt(that)
}

case class KifiExtVersion(major: Int, minor: Int, patch: Int, tag: String = "") extends KifiVersion with Ordered[KifiExtVersion] {
  def compare(that: KifiExtVersion) = compareIt(that)
}

object KifiVersion extends Logging {
  val R = """(\d{1,3})\.(\d{1,3})\.(\d{1,7})(?:-([a-zA-Z0-9-])+)?""".r
}

object KifiExtVersion {

  def apply(version: String): KifiExtVersion = {
    version match {
      case KifiVersion.R(major, minor, patch, tag) =>
        KifiExtVersion(major.toInt, minor.toInt, patch.toInt, Option(tag).getOrElse(""))
      case _ =>
        throw new Exception("Invalid kifi ext version: " + version)
    }
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KifiExtVersion] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KifiExtVersion]] = {
      stringBinder.bind(key, params) map {
        case Right(version) => Right(KifiExtVersion(version))
        case _ => Left("Unable to bind a KifiVersion")
      }
    }
    override def unbind(key: String, kifiVersion: KifiExtVersion): String = {
      stringBinder.unbind(key, kifiVersion.toString)
    }
  }

  implicit def litteral = new JavascriptLitteral[KifiExtVersion] {
    def to(value: KifiExtVersion) = value.toString
  }
}

object KifiIPhoneVersion {

  def apply(version: String): KifiIPhoneVersion = {
    version match {
      case KifiVersion.R(major, minor, patch, tag) =>
        KifiIPhoneVersion(major.toInt, minor.toInt, patch.toInt, Option(tag).getOrElse(""))
      case _ =>
        throw new Exception("Invalid kifi ext version: " + version)
    }
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KifiIPhoneVersion] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KifiIPhoneVersion]] = {
      stringBinder.bind(key, params) map {
        case Right(version) => Right(KifiIPhoneVersion(version))
        case _ => Left("Unable to bind a KifiVersion")
      }
    }
    override def unbind(key: String, kifiVersion: KifiIPhoneVersion): String = {
      stringBinder.unbind(key, kifiVersion.toString)
    }
  }

  implicit def litteral = new JavascriptLitteral[KifiIPhoneVersion] {
    def to(value: KifiIPhoneVersion) = value.toString
  }
}

case class KifiInstallation (
  id: Option[Id[KifiInstallation]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  externalId: ExternalId[KifiInstallation] = ExternalId(),
  version: KifiVersion,
  userAgent: UserAgent,
  platform: KifiInstallationPlatform,
  state: State[KifiInstallation] = KifiInstallationStates.ACTIVE
) extends ModelWithExternalId[KifiInstallation] with ModelWithState[KifiInstallation] {

  version match {
    case KifiIPhoneVersion(_, _, _, _) => require(platform == KifiInstallationPlatform.IPhone)
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
  extends StringCacheImpl[ExtensionVersionInstallationIdKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)
