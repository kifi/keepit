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

case class CommentRecipient(
  id: Option[Id[CommentRecipient]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  commentId: Id[Comment],
  userId: Option[Id[User]] = None,
  socialUserId: Option[Id[SocialUserInfo]] = None,
  email: Option[String] = None, // change me?
  state: State[CommentRecipient] = CommentRecipientStates.ACTIVE
) {
  require(userId.isDefined || socialUserId.isDefined || email.isDefined)

  def withState(state: State[CommentRecipient]) = copy(state = state)
  def withUser(userId: Id[User]) = copy(userId = Some(userId), socialUserId = None, email = None)
  def withSocialUserInfo(socialUserId: Id[SocialUserInfo]) = copy(socialUserId = Some(socialUserId), userId = None, email = None)
  def withEmail(email: String) = copy(email = Some(email), userId = None, socialUserId = None)

  def get = userId.getOrElse(socialUserId.getOrElse(email.getOrElse(throw new Exception("No recipient specified!"))))

  def save(implicit conn: Connection): CommentRecipient = {
    val entity = CommentRecipientEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

}

object CommentRecipient {

  def all(implicit conn: Connection): Seq[CommentRecipient] =
    CommentRecipientEntity.all.map(_.view)

  def get(id: Id[CommentRecipient])(implicit conn: Connection): CommentRecipient =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[CommentRecipient])(implicit conn: Connection): Option[CommentRecipient] =
    CommentRecipientEntity.get(id).map(_.view)

  def getByComment(commentId: Id[Comment])(implicit conn: Connection): Seq[CommentRecipient] =
    (CommentRecipientEntity AS "cr").map { cr => SELECT (cr.*) FROM cr WHERE (cr.commentId EQ commentId) list }.map(_.view)

  def getByUser(userId: Id[User])(implicit conn: Connection): Seq[CommentRecipient] =
    (CommentRecipientEntity AS "cr").map { cr => SELECT (cr.*) FROM cr WHERE (cr.userId EQ userId) list }.map(_.view)

  def getBySocialUser(socialUserId: Id[SocialUserInfo])(implicit conn: Connection): Seq[CommentRecipient] =
    (CommentRecipientEntity AS "cr").map { cr => SELECT (cr.*) FROM cr WHERE (cr.socialUserId EQ socialUserId) list }.map(_.view)

  def getByEmail(email: String)(implicit conn: Connection): Seq[CommentRecipient] =
    (CommentRecipientEntity AS "cr").map { cr => SELECT (cr.*) FROM cr WHERE (cr.email EQ email) list }.map(_.view)
}

object CommentRecipientStates {
  val ACTIVE = State[CommentRecipient]("active")
  val INACTIVE = State[CommentRecipient]("inactive")
}

private[model] class CommentRecipientEntity extends Entity[CommentRecipient, CommentRecipientEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val commentId = "comment_id".ID[Comment]
  val userId = "user_id".ID[User]
  val socialUserId = "social_user_id".ID[SocialUserInfo]
  val email = "email".VARCHAR(512)
  val state = "state".STATE[CommentRecipient].NOT_NULL(CommentRecipientStates.ACTIVE)

  def relation = CommentRecipientEntity

  def view(implicit conn: Connection): CommentRecipient = CommentRecipient(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    commentId = commentId(),
    userId = userId.value,
    socialUserId = socialUserId.value,
    email = email.value,
    state = state()
  )
}

private[model] object CommentRecipientEntity extends CommentRecipientEntity with EntityTable[CommentRecipient, CommentRecipientEntity] {
  override def relationName = "comment_recipient"

  def apply(view: CommentRecipient): CommentRecipientEntity = {
    val commentRecipient = new CommentRecipientEntity
    commentRecipient.id.set(view.id)
    commentRecipient.createdAt := view.createdAt
    commentRecipient.updatedAt := view.updatedAt
    commentRecipient.commentId := view.commentId
    commentRecipient.userId.set(view.userId)
    commentRecipient.socialUserId.set(view.socialUserId)
    commentRecipient.email.set(view.email)
    commentRecipient.state := view.state
    commentRecipient
  }
}


