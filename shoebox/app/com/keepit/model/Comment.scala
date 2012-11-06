package com.keepit.model

import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import play.api.libs.json._

case class Comment(
  id: Option[Id[Comment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Comment] = ExternalId(),
  normalizedURI: Id[NormalizedURI],
  userId: Id[User],
  text: String,
  permissions: State[Comment.Permission] = Comment.Permissions.PUBLIC,
  state: State[Comment] = Comment.States.ACTIVE
) {
  def withState(state: State[Comment]) = copy(state = state)
  def withPermissions(permissions: State[Comment.Permission]) = copy(permissions = permissions)
  def withExternalId(externalId: ExternalId[Comment]) = copy(externalId = externalId)
  def withNormalizedURI(normalizedURI: Id[NormalizedURI]) = copy(normalizedURI = normalizedURI)
  
  def save(implicit conn: Connection): Comment = {
    val entity = CommentEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

}

object Comment {

  def apply(url: String, userId: Id[User], text: String, title: String, permissions: State[Comment.Permission])(implicit conn: Connection): Comment = {
    val nuri = NormalizedURI.getByNormalizedUrl(url) match {
      case Some(nuri) =>
        nuri
      case None =>
        NormalizedURI(title, url)
    }
    Comment(normalizedURI = nuri.id.get, userId = userId, text = text, permissions = permissions)
  }
 

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
    (CommentEntity AS "c").map { c => SELECT (c.*) FROM c WHERE (c.userId EQ userId) list }.map(_.view)

  def getRecipients(commentId: Id[Comment])(implicit conn: Connection) = CommentRecipient.getByComment(commentId)
    
  def getByNormalizedUri(normalizedURI: Id[NormalizedURI])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map { c => SELECT (c.*) FROM c WHERE (c.normalizedURI EQ normalizedURI) list }.map(_.view)

  def getByUrl(url: String)(implicit conn: Connection): Seq[Comment] = {
    NormalizedURI.getByNormalizedUrl(url) match {
      case Some(u) => 
        getByNormalizedUri(u.id.get)
      case None =>
        Seq[Comment]()
    }
  }

  object States {
    val ACTIVE = State[Comment]("active")
    val INACTIVE = State[Comment]("inactive")
  }

  sealed trait Permission

  object Permissions {
    val PRIVATE = State[Permission]("private")
    val CONVERSATION = State[Permission]("conversation")
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
    comment.permissions := view.permissions
    comment.state := view.state
    comment
  }
}


