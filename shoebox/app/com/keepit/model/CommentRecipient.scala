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
) extends Model[CommentRecipient] {
  require(userId.isDefined || socialUserId.isDefined || email.isDefined)
  def withId(id: Id[CommentRecipient]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

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

@ImplementedBy(classOf[CommentRecipientRepoImpl])
trait CommentRecipientRepo extends Repo[CommentRecipient] {
  def getByComment(commentId: Id[Comment])(implicit session: RSession): Seq[CommentRecipient]
  def getBySocialUser(socialUserId: Id[SocialUserInfo])(implicit session: RSession): Seq[CommentRecipient]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[CommentRecipient]
  def getByEmail(email: String)(implicit session: RSession): Seq[CommentRecipient]
}

@Singleton
class CommentRecipientRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[CommentRecipient] with CommentRecipientRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[CommentRecipient](db, "comment_recipient") {
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




object CommentRecipientCxRepo {

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


