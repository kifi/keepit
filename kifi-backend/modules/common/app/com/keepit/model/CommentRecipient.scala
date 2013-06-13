package com.keepit.model

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
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
) extends Model[CommentRecipient] {
  require(userId.isDefined || socialUserId.isDefined || email.isDefined)
  def withId(id: Id[CommentRecipient]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def withState(state: State[CommentRecipient]) = copy(state = state)
  def withUser(userId: Id[User]) = copy(userId = Some(userId), socialUserId = None, email = None)
  def withSocialUserInfo(socialUserId: Id[SocialUserInfo]) = copy(socialUserId = Some(socialUserId), userId = None, email = None)
  def withEmail(email: String) = copy(email = Some(email), userId = None, socialUserId = None)
  def get = userId.getOrElse(socialUserId.getOrElse(email.getOrElse(throw new Exception("No recipient specified!"))))
}

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

object CommentRecipientStates extends States[CommentRecipient]
