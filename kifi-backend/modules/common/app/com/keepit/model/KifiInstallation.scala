package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.net.UserAgent
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import play.api.mvc.QueryStringBindable
import play.api.mvc.JavascriptLitteral

case class KifiVersion(major: Int, minor: Int, patch: Int, tag: String = "") extends Ordered[KifiVersion]  {
  assert(major >= 0 && minor >= 0 && patch >= 0)
  def compare(that: KifiVersion) =
    ((this.major - that.major) << 20) +
    ((this.minor - that.minor) << 10) +
    (this.patch - that.patch)
  override def toString = {
    Seq(major,minor,patch).mkString(".") + (if(tag != "") "-" + tag else "")
  }
}

object KifiVersion extends Logging {
  private val R = """(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:-([a-zA-Z0-9-])+)?""".r

  def apply(version: String): KifiVersion = {
    version match {
      case R(major, minor, patch, tag) =>
        KifiVersion(major.toInt, minor.toInt, patch.toInt, Option(tag).getOrElse(""))
      case _ =>
        throw new Exception("Invalid kifi version: " + version)
    }
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KifiVersion] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KifiVersion]] = {
      stringBinder.bind(key, params) map {
        case Right(version) => Right(KifiVersion(version))
        case _ => Left("Unable to bind a KifiVersion")
      }
    }
    override def unbind(key: String, kifiVersion: KifiVersion): String = {
      stringBinder.unbind(key, kifiVersion.toString)
    }
  }

  implicit def litteral = new JavascriptLitteral[KifiVersion] {
    def to(value: KifiVersion) = value.toString
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
  state: State[KifiInstallation] = KifiInstallationStates.ACTIVE
) extends ModelWithExternalId[KifiInstallation] {
  def withId(id: Id[KifiInstallation]): KifiInstallation = copy(id = Some(id))
  def withUpdateTime(time: DateTime): KifiInstallation = copy(updatedAt = time)
  def withVersion(version: KifiVersion): KifiInstallation = copy(version = version)
  def withUserAgent(userAgent: UserAgent): KifiInstallation = copy(userAgent = userAgent)
  def withState(state: State[KifiInstallation]): KifiInstallation = copy(state = state)
  def isActive: Boolean = state == KifiInstallationStates.ACTIVE
}

object KifiInstallationStates extends States[KifiInstallation]
