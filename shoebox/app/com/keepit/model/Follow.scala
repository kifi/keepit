package com.keepit.model

import org.scalaquery.ql.extended.ExtendedTable

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

case class Follow (
  id: Option[Id[Follow]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  state: State[Follow] = Follow.States.ACTIVE
) extends Logging {

  def save(implicit conn: Connection): Follow = {
    log.info("saving new follow [user: %s, uri: %s]".format(userId, uriId))
    val entity = FollowEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

  def activate = copy(state = Follow.States.ACTIVE)
  def deactivate = copy(state = Follow.States.INACTIVE)
  def isActive = state == Follow.States.ACTIVE
}

object Follow {

  def all(implicit conn: Connection): Seq[Follow] =
    throw new Exception //FollowEntity.all.map(_.view)

  def all(userId: Id[User])(implicit conn: Connection): Seq[Follow] =
    throw new Exception //(FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId) list }.map(_.view)

  def get(uriId: Id[NormalizedURI])(implicit conn: Connection): Seq[Follow] =
    throw new Exception //(FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.uriId EQ uriId AND (f.state EQ Follow.States.ACTIVE)) list }.map(_.view)

  def get(id: Id[Follow])(implicit conn: Connection): Follow =
    throw new Exception //FollowEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))

  def get(userId: Id[User], uri: NormalizedURI)(implicit conn: Connection): Option[Follow] =
    throw new Exception //get(userId, uri.id.get)

  def get(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Option[Follow] =
    throw new Exception //(FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId AND (f.uriId EQ uriId)) unique }.map(_.view)

  def getOrThrow(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Follow =
    throw new Exception //get(userId, uriId).getOrElse(throw NotFoundException(classOf[Follow], userId, uriId))

  object States {
    val ACTIVE = State[Follow]("active")
    val INACTIVE = State[Follow]("inactive")
  }

}

private[model] object FollowEntity extends ExtendedTable[(Id[_], DateTime, DateTime, Id[_], Id[_], State[_])]("follow") {
  import com.keepit.common.db.slick.FortyTwoTypeMappers
  import com.keepit.common.db.slick.FortyTwoTypeMappers._

  def id =        column[Id[_]]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[DateTime]("created_at")
  def updatedAt = column[DateTime]("updated_at")
  def userId =    column[Id[_]]("user_id")
  def uriId =     column[Id[_]]("uri_id")
  def state =     column[State[_]]("state")

  def * = id ~ createdAt ~ updatedAt ~ userId ~ uriId ~ state
  def autoInc = id.? ~ createdAt ~ updatedAt ~ userId ~ uriId ~ state <> (FollowEntity, FollowEntity.unapply _) returning id
}
