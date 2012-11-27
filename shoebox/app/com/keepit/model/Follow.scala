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

  def apply(userId: Id[User], uriId: Id[NormalizedURI]): Follow =
    Follow(userId = userId, uriId = uriId)

  def all(implicit conn: Connection): Seq[Follow] =
    FollowEntity.all.map(_.view)

  def get(id: Id[Follow])(implicit conn: Connection): Follow =
    FollowEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))

  def get(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Option[Follow] =
    (FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId AND (f.uriId EQ uriId)) unique }.map(_.view)

  def getOrThrow(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Follow =
    get(userId, uriId).getOrElse(throw NotFoundException(classOf[Follow], userId, uriId))

  def getOrCreate(userId: Id[User], uri: NormalizedURI)(implicit conn: Connection): Follow =
    getOrCreate(userId, uri.id.get)

  def getOrCreate(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Follow =
    get(userId, uriId) match {
      case Some(follow) => follow
      case None => Follow(userId, uriId).save
    }

  object States {
    val ACTIVE = State[Follow]("active")
    val INACTIVE = State[Follow]("inactive")
  }

}

private[model] class FollowEntity extends Entity[Follow, FollowEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val userId = "user_id".ID[User].NOT_NULL
  val uriId = "uri_id".ID[NormalizedURI].NOT_NULL
  val state = "state".STATE[Follow].NOT_NULL(Follow.States.ACTIVE)

  def relation = FollowEntity

  def view(implicit conn: Connection): Follow = Follow(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    userId = userId(),
    uriId = uriId(),
    state = state()
  )
}

private[model] object FollowEntity extends FollowEntity with EntityTable[Follow, FollowEntity] {
  override def relationName = "follow"

  def apply(view: Follow): FollowEntity = {
    val uri = new FollowEntity
    uri.id.set(view.id)
    uri.createdAt := view.createdAt
    uri.updatedAt := view.updatedAt
    uri.userId := view.userId
    uri.uriId := view.uriId
    uri.state := view.state
    uri
  }
}
