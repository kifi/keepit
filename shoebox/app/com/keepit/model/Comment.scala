package com.keepit.model

import java.sql.Connection
import scala.annotation.elidable
import org.joda.time.DateTime
import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.common.time.currentDateTime
import annotation.elidable.ASSERTION
import play.api.libs.json._
import com.keepit.inject._
import com.keepit.common.healthcheck._
import com.keepit.common.cache._
import akka.util.duration._

case class Comment(
  id: Option[Id[Comment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Comment] = ExternalId(),
  uriId: Id[NormalizedURI],
  urlId: Option[Id[URL]] = None, // todo(Andrew): remove Option after grandfathering process
  userId: Id[User],
  text: LargeString,
  pageTitle: String,
  parent: Option[Id[Comment]] = None,
  permissions: State[CommentPermission] = CommentPermissions.PUBLIC,
  state: State[Comment] = CommentStates.ACTIVE
) extends ModelWithExternalId[Comment] {
  def withId(id: Id[Comment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[Comment]) = copy(state = state)
  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))
  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)
}

@ImplementedBy(classOf[CommentRepoImpl])
trait CommentRepo extends Repo[Comment] with ExternalIdColumnFunction[Comment] {
  def all(permissions: State[CommentPermission])(implicit session: RSession): Seq[Comment]
  def all(permissions: State[CommentPermission], userId: Id[User])(implicit session: RSession): Seq[Comment]
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment]
  def getChildCount(commentId: Id[Comment])(implicit session: RSession): Int
  def getPublic(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment]
  def getPublicCount(uriId: Id[NormalizedURI])(implicit session: RSession): Int
  def getPrivate(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment]
  def getChildren(commentId: Id[Comment])(implicit session: RSession): Seq[Comment]
  def getMessagesWithChildrenCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Int
  def getMessages(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment]
  def count(permissions: State[CommentPermission] = CommentPermissions.PUBLIC)(implicit session: RSession): Int
  def page(page: Int, size: Int, permissions: State[CommentPermission])(implicit session: RSession): Seq[Comment]
  def getParticipantsUserIds(commentId: Id[Comment])(implicit session: RSession): Set[Id[User]]
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Comment]
}

case class CommentCountUriIdKey(normUriId: Id[NormalizedURI]) extends Key[Int] {
  val namespace = "comment_by_normuriid"
  def toKey(): String = normUriId.id.toString
}
class CommentCountUriIdCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[CommentCountUriIdKey, Int] {
  val ttl = 1 hour
  def deserialize(obj: Any): Int = obj.asInstanceOf[Int]
  def serialize(count: Int) = count
}
case class MessageWithChildrenCountUriIdUserIdKey(normUriId: Id[NormalizedURI], userId: Id[User]) extends Key[Int] {
  val namespace = "comment_by_normurlid_userid"
  def toKey(): String = normUriId.id.toString + "_" + userId.id.toString
}
class MessageWithChildrenCountUriIdUserIdCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[MessageWithChildrenCountUriIdUserIdKey, Int] {
  val ttl = 1 hour
  def deserialize(obj: Any): Int = obj.asInstanceOf[Int]
  def serialize(count: Int) = count
}

@Singleton
class CommentRepoImpl @Inject() (val db: DataBaseComponent, val commentCountCache: CommentCountUriIdCache, val messageWithChildrenCountCache: MessageWithChildrenCountUriIdUserIdCache) extends DbRepo[Comment] with CommentRepo with ExternalIdColumnDbFunction[Comment] {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.TypeMapper._
  import org.scalaquery.ql.TypeMapperDelegate._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[Comment](db, "comment") with ExternalIdColumn[Comment] {
    def uriId = column[Id[NormalizedURI]]("normalized_uri_id", O.NotNull)
    def urlId = column[Id[URL]]("url_id", O.Nullable)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def text = column[LargeString]("text", O.NotNull)
    def pageTitle = column[String]("page_title", O.NotNull)
    def parent = column[Id[Comment]]("parent", O.Nullable)
    def permissions = column[State[CommentPermission]]("permissions", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ uriId ~ urlId.? ~ userId ~ text ~ pageTitle ~ parent.? ~ permissions ~ state <> (Comment, Comment.unapply _)
  }

  override def invalidateCache(comment: Comment)(implicit session: RSession) = {
    comment.permissions match {
      case CommentPermissions.PUBLIC =>
        commentCountCache.remove(CommentCountUriIdKey(comment.uriId))
      case CommentPermissions.MESSAGE =>
        val comments = (comment.id :: comment.parent :: Nil).flatten
        val parentUserId = comment.parent.map(inject[CommentRepo].get(_).userId)
        val usersToInvalidate = (Some(comment.userId) :: parentUserId :: Nil).flatten ++ comments.flatMap(inject[CommentRecipientRepo].getByComment(_).map(_.userId).flatten)
        usersToInvalidate foreach { user =>
          messageWithChildrenCountCache.remove(MessageWithChildrenCountUriIdUserIdKey(comment.uriId, user))
        }
      case CommentPermissions.PRIVATE =>
    }
    comment
  }

  def all(permissions: State[CommentPermission])(implicit session: RSession): Seq[Comment] =
    (for(b <- table if b.permissions === permissions && b.state === CommentStates.ACTIVE) yield b).list

  def all(permissions: State[CommentPermission], userId: Id[User])(implicit session: RSession): Seq[Comment] =
    (for(b <- table if b.permissions === permissions && b.userId === userId && b.state === CommentStates.ACTIVE) yield b).list

  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment] =
    (for(b <- table if b.uriId === uriId && b.state === CommentStates.ACTIVE) yield b).list

  def getChildCount(commentId: Id[Comment])(implicit session: RSession): Int =
    (for(b <- table if b.parent === commentId && b.state === CommentStates.ACTIVE) yield b.id.count).first

  def getPublic(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment] =
    (for {
      b <- table if b.uriId === uriId && b.permissions === CommentPermissions.PUBLIC && b.parent.isNull && b.state === CommentStates.ACTIVE
    } yield b).list
  
  def getPublicCount(uriId: Id[NormalizedURI])(implicit session: RSession): Int =
    commentCountCache.getOrElse(CommentCountUriIdKey(uriId)) {
      (for {
        b <- table if b.uriId === uriId && b.permissions === CommentPermissions.PUBLIC && b.parent.isNull && b.state === CommentStates.ACTIVE
      } yield b.id.count).first
    }

  def getPrivate(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment] =
    (for {
      b <- table if b.uriId === uriId && b.userId === userId && b.permissions === CommentPermissions.PRIVATE && b.state === CommentStates.ACTIVE
    } yield b).list

  def getChildren(commentId: Id[Comment])(implicit session: RSession): Seq[Comment] =
    (for(b <- table if b.parent === commentId && b.state === CommentStates.ACTIVE) yield b).list

  def getMessages(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment] = {
    val q1 = for {
      Join(c, cr) <- table innerJoin inject[CommentRecipientRepoImpl].table on (_.id is _.commentId) if (c.uriId === uriId && cr.userId === userId && c.permissions === CommentPermissions.MESSAGE && c.parent.isNull)
    } yield (c.*)
    val q2 = for {
      c <- table if (c.uriId === uriId && c.userId === userId && c.permissions === CommentPermissions.MESSAGE && c.parent.isNull)
    } yield (c.*)
    (q1.list ++ q2.list).toSet.toSeq
  }

  def getMessagesWithChildrenCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Int = {
    messageWithChildrenCountCache.getOrElse(MessageWithChildrenCountUriIdUserIdKey(uriId, userId)) {
      val comments = getMessages(uriId, userId)
      val childrenCounts: Seq[Int] = (comments.toList map {c => getChildCount(c.id.get).toInt})
      childrenCounts.foldLeft(0)((sum, count) => sum + count) + comments.size
    }
  }

  def count(permissions: State[CommentPermission])(implicit session: RSession): Int =
    (for {
      b <- table if b.permissions === permissions && b.state === CommentStates.ACTIVE
    } yield b.id.count).first

  def page(page: Int, size: Int, permissions: State[CommentPermission])(implicit session: RSession): Seq[Comment] = {
    val q = for {
      t <- table if (t.permissions === permissions)
      _ <- Query.orderBy(t.id desc)
    } yield t
    q.drop(page * size).take(size).list
  }

  def getParticipantsUserIds(commentId: Id[Comment])(implicit session: RSession): Set[Id[User]] = {
    val comment = get(commentId)
    val head = comment.parent map get getOrElse(comment)
    (inject[CommentRecipientRepo].getByComment(head.id.get) map (_.userId)).flatten.toSet + head.userId
  }

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Comment] =
    (for(b <- table if b.urlId === urlId && b.state === CommentStates.ACTIVE) yield b).list
}

object CommentStates {
  val ACTIVE = State[Comment]("active")
  val INACTIVE = State[Comment]("inactive")
}

sealed trait CommentPermission

object CommentPermissions {
  val PRIVATE = State[CommentPermission]("private")
  val MESSAGE = State[CommentPermission]("message")
  val PUBLIC  = State[CommentPermission]("public")
}
