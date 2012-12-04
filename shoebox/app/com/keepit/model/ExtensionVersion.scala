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

case class ExtensionVersion (
  id: Option[Id[ExtensionVersion]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  version: String,
  browserInstanceId: ExternalId[ExtensionVersion],
  userAgent: UserAgent,
  state: State[ExtensionVersion] = ExtensionVersion.States.ACTIVE
) extends Logging {

  def save(implicit conn: Connection): ExtensionVersion = {
    val entity = ExtensionVersionEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

  def withNewVersion(version: String) = copy(version = version)
  def withUserAgent(agent: String) = copy(userAgent = UserAgent(agent))
  def withBrowserInstanceId(browserInstanceId: String) = copy(browserInstanceId = ExternalId[ExtensionVersion](browserInstanceId))
  def withBrowserInstanceId(browserInstanceId: ExternalId[ExtensionVersion]) = copy(browserInstanceId = browserInstanceId)

}

object ExtensionVersion {

  def all(implicit conn: Connection): Seq[ExtensionVersion] =
    ExtensionVersionEntity.all.map(_.view)

  def all(userId: Id[User])(implicit conn: Connection): Seq[ExtensionVersion] =
    (ExtensionVersionEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId) list }.map(_.view)

  def get(id: Id[ExtensionVersion])(implicit conn: Connection): ExtensionVersion =
    ExtensionVersionEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))

  def getOpt(userId: Id[User], browserInstanceId: ExternalId[ExtensionVersion])(implicit conn: Connection): Option[ExtensionVersion] =
    (ExtensionVersionEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId AND (f.browserInstanceId EQ browserInstanceId)) unique }.map(_.view)

  object States {
    val ACTIVE = State[ExtensionVersion]("active")
    val INACTIVE = State[ExtensionVersion]("inactive")
  }

}

private[model] class ExtensionVersionEntity extends Entity[ExtensionVersion, ExtensionVersionEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val userId = "user_id".ID[User].NOT_NULL
  val version = "version".VARCHAR(16).NOT_NULL
  val browserInstanceId = "browser_instance_id".EXTERNAL_ID[ExtensionVersion].NOT_NULL
  val userAgent = "user_agent".VARCHAR(512).NOT_NULL //could be more, I think we can trunk it at that
  val state = "state".STATE[ExtensionVersion].NOT_NULL(ExtensionVersion.States.ACTIVE)

  def relation = ExtensionVersionEntity

  def view(implicit conn: Connection): ExtensionVersion = ExtensionVersion(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    userId = userId(),
    version = version(),
    userAgent = UserAgent(userAgent()),
    browserInstanceId = browserInstanceId(),
    state = state()
  )
}

private[model] object ExtensionVersionEntity extends ExtensionVersionEntity with EntityTable[ExtensionVersion, ExtensionVersionEntity] {
  override def relationName = "extension_version"

  def apply(view: ExtensionVersion): ExtensionVersionEntity = {
    val uri = new ExtensionVersionEntity
    uri.id.set(view.id)
    uri.createdAt := view.createdAt
    uri.updatedAt := view.updatedAt
    uri.userId := view.userId
    uri.version := view.version
    uri.browserInstanceId := view.browserInstanceId
    uri.userAgent := view.userAgent.userAgent
    uri.state := view.state
    uri
  }
}
