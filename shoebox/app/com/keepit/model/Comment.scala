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
import ru.circumflex.orm.Projection
import ru.circumflex.orm.RelationNode
import ru.circumflex.orm.RelationNode.toRelation
import ru.circumflex.orm.SELECT
import ru.circumflex.orm.str2expr
import ru.circumflex.orm.COUNT

case class Comment(
  id: Option[Id[Comment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Comment] = ExternalId(),
  uriId: Id[NormalizedURI],
  userId: Id[User],
  text: String,
  pageTitle: String,
  parent: Option[Id[Comment]] = None,
  permissions: State[Comment.Permission] = Comment.Permissions.PUBLIC,
  state: State[Comment] = Comment.States.ACTIVE) {

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

  def all(permissions: State[Comment.Permission])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map {c => SELECT (c.*) FROM c WHERE (c.permissions EQ permissions) list} map (_.view)

  def all(permissions: State[Comment.Permission], userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map {c => SELECT (c.*) FROM c WHERE ((c.userId EQ userId) AND (c.permissions EQ permissions)) list} map (_.view)

  def get(id: Id[Comment])(implicit conn: Connection): Comment =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[Comment])(implicit conn: Connection): Option[Comment] =
    CommentEntity.get(id).map(_.view)

  def get(id: ExternalId[Comment])(implicit conn: Connection): Comment =
    (CommentEntity AS "c").map {c => SELECT (c.*) FROM c WHERE (c.externalId EQ id) unique}
      .map(_.view).getOrElse(throw NotFoundException(id))

  def getRecipients(commentId: Id[Comment])(implicit conn: Connection) =
    CommentRecipient.getByComment(commentId)

  def getParticipantsUserIds(comment: Comment)(implicit conn: Connection): Set[Id[User]] = {
    val head = comment.parent map (Comment.get(_)) getOrElse(comment)
    (CommentRecipient.getByComment(head.id.get) map (_.userId)).flatten.toSet + head.userId
  }

  def getPublic(uriId: Id[NormalizedURI])(implicit conn: Connection): Seq[Comment] =
    selectPublic({c => c.*}, uriId).list.map(_.view)

  def getPublicCount(uriId: Id[NormalizedURI])(implicit conn: Connection): Long =
    selectPublic({c => COUNT(c.id)}, uriId).unique.get

  private def selectPublic[T](
      project: RelationNode[Id[Comment],CommentEntity] => Projection[T],
      uriId: Id[NormalizedURI])(implicit conn: Connection) = {
    val c = CommentEntity AS "c"
    SELECT (project(c)) FROM c WHERE (
        (c.uriId EQ uriId) AND
        (c.permissions EQ Comment.Permissions.PUBLIC) AND
        (c.state EQ States.ACTIVE) AND
        (c.parent IS_NULL))
  }

  def getPrivate(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    selectPrivate({c => c.*}, uriId, userId).list.map(_.view)

  def getPrivateCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Long =
    selectPrivate({c => COUNT(c.id)}, uriId, userId).unique.get

  private def selectPrivate[T](
      project: RelationNode[Id[Comment],CommentEntity] => Projection[T],
      uriId: Id[NormalizedURI],
      userId: Id[User])(implicit conn: Connection) = {
    val c = CommentEntity AS "c"
    SELECT (project(c)) FROM c WHERE (
        (c.uriId EQ uriId) AND
        (c.userId EQ userId) AND
        (c.permissions EQ Comment.Permissions.PRIVATE))
  }

  def getMessages(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    selectMessages({c => c.*}, uriId, userId).list.map(_.view)

  def getMessagesWithChildrenCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Int = {
    val comments = selectMessages({c => c.*}, uriId, userId).list.map(_.view).toSet
    val childrenCounts: Seq[Int] = (comments.toList map {c => getChildCount(c.id.get).toInt})
    childrenCounts.foldLeft(0)((sum, count) => sum + count) + comments.size
  }

  def getMessageCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Long =
    selectMessages({c => c.id}, uriId, userId).list.size

  private def selectMessages[T](
      project: RelationNode[Id[Comment],CommentEntity] => Projection[T],
      uriId: Id[NormalizedURI],
      userId: Id[User])(implicit conn: Connection) = {
    val c = CommentEntity AS "c"
    val cr = CommentRecipientEntity AS "cr"

    // Get all messages *from* and/or *to* the user
    // (side effect: User cannot be removed from own messages)
    (SELECT (project(c)) FROM (c JOIN cr).ON("c.id = cr.comment_id") WHERE (
        (c.uriId EQ uriId) AND
        (cr.userId EQ userId) AND
        (c.permissions EQ Comment.Permissions.MESSAGE) AND
        (c.parent IS_NULL)))
    .UNION (SELECT (project(c)) FROM c WHERE (
        (c.uriId EQ uriId) AND
        (c.userId EQ userId) AND
        (c.permissions EQ Comment.Permissions.MESSAGE) AND
        (c.parent IS_NULL)))
  }

  def getChildren(commentId: Id[Comment])(implicit conn: Connection): Seq[Comment] =
    selectChildren({c => c.*}, commentId).list.map(_.view)

  def getChildCount(commentId: Id[Comment])(implicit conn: Connection): Long =
    selectChildren({c => COUNT(c.id)}, commentId).unique.get

  private def selectChildren[T](
      project: RelationNode[Id[Comment],CommentEntity] => Projection[T],
      commentId: Id[Comment])(implicit conn: Connection) = {
    val c = CommentEntity AS "c"
    SELECT (project(c)) FROM c WHERE (c.parent EQ commentId)
  }

  def page(page: Int = 0, size: Int = 20, permissions: State[Comment.Permission] = Comment.Permissions.PUBLIC)(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map { c => SELECT (c.*) FROM c  WHERE (c.permissions EQ permissions)  LIMIT size OFFSET (page * size) ORDER_BY (c.id DESC) list }.map(_.view)

  def count( permissions: State[Comment.Permission] = Comment.Permissions.PUBLIC)(implicit conn: Connection): Long =
    (CommentEntity AS "c").map(c => SELECT(COUNT(c.id)).FROM(c).WHERE (c.permissions EQ permissions).unique).get

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
  val uriId = "normalized_uri_id".ID[NormalizedURI].NOT_NULL
  val userId = "user_id".ID[User]
  val pageTitle = "page_title".VARCHAR(1024).NOT_NULL
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
    uriId = uriId(),
    userId = userId(),
    pageTitle = pageTitle(),
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
    comment.uriId := view.uriId
    comment.userId := view.userId
    comment.pageTitle := view.pageTitle
    comment.text := view.text
    comment.parent.set(view.parent)
    comment.permissions := view.permissions
    comment.state := view.state
    comment
  }
}


