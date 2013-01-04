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
import com.keepit.common.db.slick.FortyTwoTypeMappers
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import org.scalaquery.ql.SimpleExpression

object FollowStates {
  val ACTIVE = State[Follow]("active")
  val INACTIVE = State[Follow]("inactive")
}

case class Follow (
  id: Option[Id[Follow]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  state: State[Follow] = FollowStates.ACTIVE
){

//  def save(implicit conn: Connection): Follow = throw new Exception
//  def save(implicit conn: Connection): Follow = {
//    log.info("saving new follow [user: %s, uri: %s]".format(userId, uriId))
//    val entity = FollowEntity(this.copy(updatedAt = currentDateTime))
//    assert(1 == entity.save())
//    entity.view
//  }

  def activate = copy(state = FollowStates.ACTIVE)
  def deactivate = copy(state = FollowStates.INACTIVE)
  def isActive = state == FollowStates.ACTIVE
}

private[model] object FollowEntity extends ExtendedTable[Follow]("follow") {
  def id =        column[Id[Follow]]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[DateTime]("created_at")
  def updatedAt = column[DateTime]("updated_at")
  def userId =    column[Id[User]]("user_id")
  def uriId =     column[Id[NormalizedURI]]("uri_id")
  def state =     column[State[Follow]]("state")

  def * = id.? ~ createdAt ~ updatedAt ~ userId ~ uriId ~ state <> (Follow, Follow.unapply _)
  def lastInsertedId: Id[Follow] = SimpleExpression.nullary[Id[Follow]]("scope_identity") // or LAST_INSERT_ID for mysql
}

class FollowRepo {

  def all(implicit conn: Connection): Seq[Follow] =
    throw new Exception //FollowEntity.all.map(_.view)

  def all(userId: Id[User])(implicit conn: Connection): Seq[Follow] =
    throw new Exception //(FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId) list }.map(_.view)

  def get(uriId: Id[NormalizedURI])(implicit conn: Connection): Seq[Follow] =
    throw new Exception //(FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.uriId EQ uriId AND (f.state EQ FollowStates.ACTIVE)) list }.map(_.view)

  def get(id: Id[Follow])(implicit conn: Connection): Follow =
    throw new Exception //FollowEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))

  def get(userId: Id[User], uri: NormalizedURI)(implicit conn: Connection): Option[Follow] =
    throw new Exception //get(userId, uri.id.get)

  def get(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Option[Follow] =
    throw new Exception //(FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId AND (f.uriId EQ uriId)) unique }.map(_.view)

  def getOrThrow(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Follow =
    throw new Exception //get(userId, uriId).getOrElse(throw NotFoundException(classOf[Follow], userId, uriId))

}

