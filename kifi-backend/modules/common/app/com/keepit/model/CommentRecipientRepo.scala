package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[CommentRecipientRepoImpl])
trait CommentRecipientRepo extends Repo[CommentRecipient] {
  def getByComment(commentId: Id[Comment])(implicit session: RSession): Seq[CommentRecipient]
  def getBySocialUser(socialUserId: Id[SocialUserInfo])(implicit session: RSession): Seq[CommentRecipient]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[CommentRecipient]
  def getByEmail(email: String)(implicit session: RSession): Seq[CommentRecipient]
}

@Singleton
class CommentRecipientRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[CommentRecipient] with CommentRecipientRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[CommentRecipient](db, "comment_recipient") {
    def commentId = column[Id[Comment]]("comment_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def socialUserId = column[Id[SocialUserInfo]]("social_user_id", O.Nullable)
    def email = column[String]("email", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ commentId ~ userId.? ~ socialUserId.? ~ email.? ~ state <> (CommentRecipient, CommentRecipient.unapply _)
  }

  def getByComment(commentId: Id[Comment])(implicit session: RSession): Seq[CommentRecipient] =
    (for(f <- table if f.commentId === commentId && f.state === CommentRecipientStates.ACTIVE) yield f).list

  def getBySocialUser(socialUserId: Id[SocialUserInfo])(implicit session: RSession): Seq[CommentRecipient] =
    (for(f <- table if f.socialUserId === socialUserId && f.state === CommentRecipientStates.ACTIVE) yield f).list

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[CommentRecipient] =
    (for(f <- table if f.userId === userId && f.state === CommentRecipientStates.ACTIVE) yield f).list

  def getByEmail(email: String)(implicit session: RSession): Seq[CommentRecipient] =
    (for(f <- table if f.email === email && f.state === CommentRecipientStates.ACTIVE) yield f).list
}
