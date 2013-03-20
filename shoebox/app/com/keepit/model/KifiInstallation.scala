package com.keepit.model

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import com.keepit.common.logging.Logging
import play.api.mvc.QueryStringBindable
import play.api.mvc.JavascriptLitteral

case class UserAgent(val userAgent: String) {//here we'll have some smartness about parsing the string
  if(userAgent.length >  UserAgent.MAX_USER_AGENT_LENGTH) throw new Exception("trunking user agent string since its too long: %s".format(userAgent))
}

object UserAgent extends Logging {

  val MAX_USER_AGENT_LENGTH = 512

  def fromString(userAgent: String): UserAgent = if(userAgent.length >  MAX_USER_AGENT_LENGTH) {
      log.warn("trunking user agent string since its too long: %s".format(userAgent))
      new UserAgent(userAgent.substring(0, MAX_USER_AGENT_LENGTH - 3) + "...")
    } else {
      new UserAgent(userAgent)
    }
}

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
  def withId(id: Id[KifiInstallation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withVersion(version: KifiVersion) = copy(version = version)
  def withUserAgent(userAgent: UserAgent) = copy(userAgent = userAgent)
}

@ImplementedBy(classOf[KifiInstallationRepoImpl])
trait KifiInstallationRepo extends Repo[KifiInstallation] with ExternalIdColumnFunction[KifiInstallation] {
  def all(userId: Id[User])(implicit session: RSession): Seq[KifiInstallation]
  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation]
}

@Singleton
class KifiInstallationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[KifiInstallation] with KifiInstallationRepo with ExternalIdColumnDbFunction[KifiInstallation] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[KifiInstallation](db, "kifi_installation") with ExternalIdColumn[KifiInstallation] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def version = column[KifiVersion]("version", O.NotNull)
    def userAgent = column[UserAgent]("user_agent", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ externalId ~ version ~ userAgent ~ state <> (KifiInstallation, KifiInstallation.unapply _)
  }

  def all(userId: Id[User])(implicit session: RSession): Seq[KifiInstallation] =
    (for(k <- table if k.userId === userId) yield k).list

  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation] =
    (for(k <- table if k.userId === userId && k.externalId === externalId) yield k).firstOption
}

object KifiInstallationStates extends States[KifiInstallation]
