package com.keepit.model

import java.sql.Connection

import scala.annotation.elidable

import org.joda.time.DateTime

import com.keepit.common.db.Entity
import com.keepit.common.db.EntityTable
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.NotFoundException
import com.keepit.common.db.State
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.common.time.currentDateTime

import annotation.elidable.ASSERTION
import ru.circumflex.orm.Predicate.toAggregateHelper
import ru.circumflex.orm.RelationNode.toRelation
import ru.circumflex.orm.SELECT
import ru.circumflex.orm.str2expr

case class Comment(
  id: Option[Id[Comment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Comment] = ExternalId(),
  normalizedURI: Id[NormalizedURI],
  userId: Id[User],
  text: String,
  parent: Option[Id[Comment]] = None,
  permissions: State[Comment.Permission] = Comment.Permissions.PUBLIC,
  state: State[Comment] = Comment.States.ACTIVE
) {
  def withState(state: State[Comment]) = copy(state = state)

  def save(implicit conn: Connection): Comment = {
    val entity = CommentEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

}

object Comment {


  def all(implicit conn: Connection): Seq[Comment] =
    CommentEntity.all.map(_.view)

  def get(id: Id[Comment])(implicit conn: Connection): Comment =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[Comment])(implicit conn: Connection): Option[Comment] =
    CommentEntity.get(id).map(_.view)

  def get(externalId: ExternalId[Comment])(implicit conn: Connection): Comment =
    getOpt(externalId).getOrElse(throw NotFoundException(externalId))

  def getOpt(externalId: ExternalId[Comment])(implicit conn: Connection): Option[Comment] =
    (CommentEntity AS "c").map { c => SELECT (c.*) FROM c WHERE (c.externalId EQ externalId) unique }.map(_.view)

  def getByUser(userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map { c =>
      SELECT (c.*) FROM c WHERE (
        (c.userId EQ userId) AND
        (c.permissions EQ Comment.Permissions.PUBLIC) AND
        (c.state EQ States.ACTIVE)
      ) list
    }.map(_.view)

  def getRecipients(commentId: Id[Comment])(implicit conn: Connection) = CommentRecipient.getByComment(commentId)

  def getPublicByNormalizedUri(normalizedURI: Id[NormalizedURI])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map { c =>
      SELECT (c.*) FROM c WHERE (
        (c.normalizedURI EQ normalizedURI) AND
        (c.permissions EQ Comment.Permissions.PUBLIC) AND
        (c.state EQ States.ACTIVE)
      ) list
    }.map(_.view)

  def getPrivateByNormalizedUri(normalizedURI: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map { c =>
      SELECT (c.*) FROM c WHERE (
        (c.normalizedURI EQ normalizedURI) AND
        (c.permissions EQ Comment.Permissions.PRIVATE) AND
        (c.userId EQ userId)
      ) list
    }.map(_.view)

  def getMessagesByNormalizedUri(normalizedURI: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Seq[Comment] = {
      val c = CommentEntity AS "c"
      val cr = CommentRecipientEntity AS "cr"

      // Get all messages by the user, and where the user is listed as a recipient (side effect: User cannot be removed from own messages)
      ((SELECT (c.*) FROM ((cr JOIN c).ON("c.id = cr.comment_id")) WHERE (
        (c.normalizedURI EQ normalizedURI) AND
        (cr.userId EQ userId) AND
        (c.permissions EQ Comment.Permissions.MESSAGE))
        UNION
       (SELECT (c.*) FROM c WHERE (
        (c.normalizedURI EQ normalizedURI) AND
        (c.userId EQ userId) AND
        (c.permissions EQ Comment.Permissions.MESSAGE))
       )
      ) list) map (_.view) distinct
  }

  def getChildren(commentId: Id[Comment])(implicit conn: Connection): Seq[Comment] = {
    (CommentEntity AS "c").map { c =>
      SELECT (c.*) FROM c WHERE (c.parent EQ commentId) list
    }.map(_.view)
  }

  object States {
    val ACTIVE = State[Comment]("active")
    val INACTIVE = State[Comment]("inactive")
  }

  sealed trait Permission

  object Permissions {
    val PRIVATE = State[Permission]("private")
    val MESSAGE = State[Permission]("message")
    val PUBLIC = State[Permission]("public")
  }
}

private[model] class CommentEntity extends Entity[Comment, CommentEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[Comment].NOT_NULL(ExternalId())
  val normalizedURI = "normalized_uri_id".ID[NormalizedURI].NOT_NULL
  val userId = "user_id".ID[User]
  val text = "text".CLOB.NOT_NULL
  val parent = "parent".ID[Comment]
  val permissions = "permissions".STATE[Comment.Permission].NOT_NULL(Comment.Permissions.PUBLIC)
  val state = "state".STATE[Comment].NOT_NULL(Comment.States.ACTIVE)

  def relation = CommentEntity

  def view(implicit conn: Connection): Comment = Comment(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    normalizedURI = normalizedURI(),
    userId = userId(),
    text = text(),
    parent = parent.value,
    permissions = permissions(),
    state = state()
  )
}

private[model] object CommentEntity extends CommentEntity with EntityTable[Comment, CommentEntity] {
  override def relationName = "comment"

  def apply(view: Comment): CommentEntity = {
    val comment = new CommentEntity
    comment.id.set(view.id)
    comment.createdAt := view.createdAt
    comment.updatedAt := view.updatedAt
    comment.externalId := view.externalId
    comment.normalizedURI := view.normalizedURI
    comment.userId := view.userId
    comment.text := view.text
    comment.parent.set(view.parent)
    comment.permissions := view.permissions
    comment.state := view.state
    comment
  }
}


