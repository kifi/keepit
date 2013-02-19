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
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import com.keepit.common.logging.Logging
import play.api.libs.json._

case class CommentRead (
  id: Option[Id[CommentRead]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  parentId: Option[Id[Comment]] = None,
  lastReadId: Id[Comment],
  state: State[CommentRead] = CommentReadStates.ACTIVE
) extends Model[CommentRead] {
  def withId(id: Id[CommentRead]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[CommentRead]) = copy(state = state)
  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)
  def withLastReadId(commentId: Id[Comment]) = copy(lastReadId = commentId)
}

@ImplementedBy(classOf[CommentReadRepoImpl])
trait CommentReadRepo extends Repo[CommentRead] {
  def getUnreadMessages(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment]
  def hasUnreadComments(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
  def getCommentRead(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[CommentRead]
  def getMessagesRead(userId: Id[User], parentId: Id[Comment])(implicit session: RSession): Option[CommentRead]
}

@Singleton
class CommentReadRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[CommentRead] with CommentReadRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[CommentRead](db, "comment_read") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def parentId = column[Id[Comment]]("parent_id", O.Nullable)
    def lastReadId = column[Id[Comment]]("last_read_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ uriId ~ parentId.? ~ lastReadId ~ state <> (CommentRead, CommentRead.unapply _)
  }

  def getMessagesRead(userId: Id[User], parentId: Id[Comment])(implicit session: RSession): Option[CommentRead] =
    (for (f <- table if f.userId === userId && f.parentId === parentId && f.state === CommentReadStates.ACTIVE) yield f).firstOption

  private def getLatestChildId(parentId: Id[Comment])(implicit session: RSession): Id[Comment] = {
    (inject[CommentRepo].getChildren(parentId).map(_.id.get) :+ parentId).maxBy(_.id)
  }

  def getUnreadMessages(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment] = {
    val messages = inject[CommentRepo].getMessages(uriId, userId) // all message threads with this user

    messages.map { message =>
      getMessagesRead(userId, message.id.get) match {
        case Some(commentRead) if commentRead.lastReadId.id < getLatestChildId(message.id.get).id =>
          Some(message)
        case Some(commentRead)=>
          None
        case None =>
          Some(message)
      }
    }.flatten
  }

  def getCommentRead(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[CommentRead] =
    (for (f <- table if f.userId === userId && f.uriId === uriId && f.parentId.isNull && f.state === CommentReadStates.ACTIVE) yield f).firstOption

  def hasUnreadComments(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    val lastCommentIdOpt = inject[CommentRepo].getLastPublicIdByConnection(userId, uriId)
    lastCommentIdOpt match {
      case Some(lastCommentId) =>
        getCommentRead(userId, uriId) match {
          case Some(commentRead)  => // ∃ messages, ∃ CommentRead
            commentRead.lastReadId.id < lastCommentId.id
          case None => // ∃ messages, !∃ CommentRead
            true
        }
      case None => // !∃ messages
        false
    }
  }
}

object CommentReadStates extends States[CommentRead]
