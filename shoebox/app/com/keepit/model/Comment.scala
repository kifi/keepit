package com.keepit.model

import java.sql.Connection
import scala.annotation.elidable
import org.joda.time.DateTime
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time.Clock
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

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import collection.SeqProxy
import com.keepit.common.logging._


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
  def withId(id: Id[Comment]): Comment = copy(id = Some(id))
  def withUpdateTime(now: DateTime): Comment = copy(updatedAt = now)
  def withState(state: State[Comment]): Comment = copy(state = state)
  def withUrlId(urlId: Id[URL]): Comment = copy(urlId = Some(urlId))
  def withNormUriId(normUriId: Id[NormalizedURI]): Comment = copy(uriId = normUriId)
  def isActive: Boolean = state == CommentStates.ACTIVE
}

@ImplementedBy(classOf[CommentRepoImpl])
trait CommentRepo extends Repo[Comment] with ExternalIdColumnFunction[Comment] {
  def all(permissions: State[CommentPermission])(implicit session: RSession): Seq[Comment]
  def all(permissions: State[CommentPermission], userId: Id[User])(implicit session: RSession): Seq[Comment]
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment]
  def getPublic(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment]
  def getPublicIdsByConnection(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[Comment]]
  def getLastPublicIdByConnection(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[Id[Comment]]
  def getPublicCount(uriId: Id[NormalizedURI])(implicit session: RSession): Int
  def getPrivate(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment]
  def getChildren(commentId: Id[Comment])(implicit session: RSession): Seq[Comment]
  def getParentMessages(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment]
  def getParentByUriParticipants(normUri: Id[NormalizedURI], recipients: Set[Id[User]])(implicit session: RSession): Option[Id[Comment]]
  def count(permissions: State[CommentPermission] = CommentPermissions.PUBLIC)(implicit session: RSession): Int
  def page(page: Int, size: Int, permissions: State[CommentPermission])(implicit session: RSession): Seq[Comment]
  def getParticipantsUserIds(commentId: Id[Comment])(implicit session: RSession): Set[Id[User]]
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Comment]
  def getPublicIdsCreatedBefore(uriId: Id[NormalizedURI], time: DateTime)(implicit session: RSession): Seq[Id[Comment]]
  def getMessageIdsCreatedBefore(uriId: Id[NormalizedURI], parentId: Id[Comment], time: DateTime)(implicit session: RSession): Seq[Id[Comment]]
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

@Singleton
class CommentRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val commentCountCache: CommentCountUriIdCache,
  userConnectionRepo: UserConnectionRepo,
  commentRecipientRepoImpl: CommentRecipientRepoImpl)
    extends DbRepo[Comment] with CommentRepo with ExternalIdColumnDbFunction[Comment] with Logging {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[Comment](db, "comment") with ExternalIdColumn[Comment] {
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

  def getPublic(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Comment] =
    (for {
      b <- table if b.uriId === uriId && b.permissions === CommentPermissions.PUBLIC && b.parent.isNull && b.state === CommentStates.ACTIVE
    } yield b).sortBy(_.createdAt asc).list

  def getPublicIdsByConnection(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[Comment]] = {
    val friends = userConnectionRepo.getConnectedUsers(userId)
    val commentsOnPage = (for {
      c <- table  if c.uriId === uriId && c.permissions === CommentPermissions.PUBLIC && c.state === CommentStates.ACTIVE
    } yield c).list
    commentsOnPage.filter(c => friends.contains(c.userId)).map(_.id.get)
  }

  def getLastPublicIdByConnection(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[Id[Comment]] = {
    getPublicIdsByConnection(userId, uriId) match {
      case Nil => None
      case commentList => Some(commentList.maxBy(_.id))
    }
  }

  def getPublicCount(uriId: Id[NormalizedURI])(implicit session: RSession): Int =
    commentCountCache.getOrElse(CommentCountUriIdKey(uriId)) {
      Query((for {
        b <- table if b.uriId === uriId && b.permissions === CommentPermissions.PUBLIC && b.parent.isNull && b.state === CommentStates.ACTIVE
      } yield b.id).countDistinct).first
    }

  def getPrivate(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment] =
    (for {
      b <- table if b.uriId === uriId && b.userId === userId && b.permissions === CommentPermissions.PRIVATE && b.state === CommentStates.ACTIVE
    } yield b).list

  def getChildren(commentId: Id[Comment])(implicit session: RSession): Seq[Comment] =
    (for(b <- table if b.parent === commentId && b.state === CommentStates.ACTIVE) yield b).sortBy(_.createdAt asc).list

  def getParentMessages(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Comment] = {
    val q1 = for {
      (c, cr) <- table innerJoin commentRecipientRepoImpl.table on (_.id is _.commentId) if (c.uriId === uriId && cr.userId === userId && c.permissions === CommentPermissions.MESSAGE && c.parent.isNull)
    } yield (c.*)
    val q2 = for {
      c <- table if (c.uriId === uriId && c.userId === userId && c.permissions === CommentPermissions.MESSAGE && c.parent.isNull)
    } yield (c.*)
    (q1.list ++ q2.list).toSet.toSeq
  }

  def getParentByUriParticipants(normUri: Id[NormalizedURI], recipients: Set[Id[User]])(implicit session: RSession): Option[Id[Comment]] = {
      val conn = session.conn
      val st = conn.createStatement()

      val recipientIn = recipients.map(_.id).mkString(",")
      val recipientLength = recipients.size - 1 // there is one less CommentRecipient due to the author

      val sql =
        s"""
          select c.id as id from comment c, comment_recipient r
          where c.id = r.comment_id
            and c.permissions = 'message'
            and c.parent is null
            and c.normalized_uri_id = ${normUri.id}
            and c.user_id in ($recipientIn)
          group by c.id
          having count(*) = $recipientLength
            and sum(r.user_id in ($recipientIn)) = $recipientLength
          order by id limit 1;
        """
      val rs = st.executeQuery(sql)

      if(rs.next) {
        Some(Id[Comment](rs.getLong("id")))
      } else {
        None
      }
  }

  def count(permissions: State[CommentPermission])(implicit session: RSession): Int =
    Query((for {
      b <- table if b.permissions === permissions && b.state === CommentStates.ACTIVE
    } yield b.id).countDistinct).first

  def page(page: Int, size: Int, permissions: State[CommentPermission])(implicit session: RSession): Seq[Comment] = {
    val q = for {
      t <- table if (t.permissions === permissions)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def getParticipantsUserIds(commentId: Id[Comment])(implicit session: RSession): Set[Id[User]] = {
    val comment = get(commentId)
    val head = comment.parent map get getOrElse(comment)
    (commentRecipientRepoImpl.getByComment(head.id.get) map (_.userId)).flatten.toSet + head.userId
  }

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Comment] =
    (for(b <- table if b.urlId === urlId && b.state === CommentStates.ACTIVE) yield b).list

  def getPublicIdsCreatedBefore(uriId: Id[NormalizedURI], time: DateTime)(implicit session: RSession): Seq[Id[Comment]] =
    (for(b <- table if b.uriId === uriId && b.permissions === CommentPermissions.PUBLIC && b.createdAt < time) yield b.id).list

  def getMessageIdsCreatedBefore(uriId: Id[NormalizedURI], parentId: Id[Comment], time: DateTime)(implicit session: RSession): Seq[Id[Comment]] =
    (for(c <- table if c.uriId === uriId && c.permissions === CommentPermissions.MESSAGE && (c.id === parentId || c.parent === parentId) && c.createdAt < time) yield c.id).list
}

object CommentStates extends States[Comment]

sealed trait CommentPermission

object CommentPermissions {
  val PRIVATE = State[CommentPermission]("private")
  val MESSAGE = State[CommentPermission]("message")
  val PUBLIC  = State[CommentPermission]("public")
}

@Singleton
class CommentFormatter {
  private val lookHereLinkRe = """(\[(?:\\\]|[^\]])*\])\(x-kifi-sel:(?:\\\)|[^)])*\)""".r

  // e.g. """hey [look here](x-kifi-sel:body>div#page.watch>div:nth-child(4\)>div#watch7)""" => "hey [look here]"
  def toPlainText(text: String): String =
    lookHereLinkRe.replaceAllIn(text, m => m.group(1).replaceAll("""\\(.)""", "$1"))
}
