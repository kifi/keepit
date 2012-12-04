package com.keepit.model

import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.db.StateException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import com.keepit.common.logging.Logging

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

case class KifiVersion(major: Int, minor: Int, patch: Int = 0, tag: String = "") extends Ordered[KifiVersion]  {
  assert(major >= 0 && minor >= 0 && patch >= 0)
  def compare(that: KifiVersion) =
    (this.major - that.major) * 10000 +
    (this.minor - that.minor) * 100 +
    (this.patch - that.patch)
  override def toString = {
    Seq(major,minor,patch).mkString(".") + (if(tag != "") "-" + tag else "")
  }
}
object KifiVersion {
  def apply(version: String): KifiVersion = {
    try {
      def safeVersion(v: Array[String], i: Int) = if(v.length <= i) 0 else v(i).toInt

      val t = version.split('-')
      assert(t.length > 0)
      val v = t(0).split('.')
      assert(v.length > 1)
      val tag = if(t.length > 1) t(1) else ""
      KifiVersion(safeVersion(v,0), safeVersion(v,1), safeVersion(v,2), tag)
    } catch {
      case _ => throw new Exception("Invalid Kifi Version")
    }
  }
}

case class KifiInstallation (
  id: Option[Id[KifiInstallation]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  version: KifiVersion,
  externalId: ExternalId[KifiInstallation],
  userAgent: UserAgent,
  state: State[KifiInstallation] = KifiInstallation.States.ACTIVE
) extends Logging {

  def save(implicit conn: Connection): KifiInstallation = {
    val entity = KifiInstallationEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

  def withNewVersion(version: KifiVersion) = copy(version = version)
  def withUserAgent(agent: String) = copy(userAgent = UserAgent(agent))
}

object KifiInstallation {

  def all(implicit conn: Connection): Seq[KifiInstallation] =
    KifiInstallationEntity.all.map(_.view)

  def all(userId: Id[User])(implicit conn: Connection): Seq[KifiInstallation] =
    (KifiInstallationEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId) list }.map(_.view)

  def get(id: Id[KifiInstallation])(implicit conn: Connection): KifiInstallation =
    KifiInstallationEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))

  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit conn: Connection): Option[KifiInstallation] =
    (KifiInstallationEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId AND (f.externalId EQ externalId)) unique }.map(_.view)

  object States {
    val ACTIVE = State[KifiInstallation]("active")
    val INACTIVE = State[KifiInstallation]("inactive")
  }

}

private[model] class KifiInstallationEntity extends Entity[KifiInstallation, KifiInstallationEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val userId = "user_id".ID[User].NOT_NULL
  val version = "version".VARCHAR(16).NOT_NULL
  val externalId = "browser_instance_id".EXTERNAL_ID[KifiInstallation].NOT_NULL
  val userAgent = "user_agent".VARCHAR(512).NOT_NULL //could be more, I think we can trunk it at that
  val state = "state".STATE[KifiInstallation].NOT_NULL(KifiInstallation.States.ACTIVE)

  def relation = KifiInstallationEntity

  def view(implicit conn: Connection): KifiInstallation = KifiInstallation(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    userId = userId(),
    version = KifiVersion(version()),
    userAgent = UserAgent(userAgent()),
    externalId = externalId(),
    state = state()
  )
}

private[model] object KifiInstallationEntity extends KifiInstallationEntity with EntityTable[KifiInstallation, KifiInstallationEntity] {
  override def relationName = "kifi_installation"

  def apply(view: KifiInstallation): KifiInstallationEntity = {
    val uri = new KifiInstallationEntity
    uri.id.set(view.id)
    uri.createdAt := view.createdAt
    uri.updatedAt := view.updatedAt
    uri.userId := view.userId
    uri.version := view.version.toString
    uri.externalId := view.externalId
    uri.userAgent := view.userAgent.userAgent
    uri.state := view.state
    uri
  }
}
