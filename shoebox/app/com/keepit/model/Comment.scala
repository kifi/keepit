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
import ru.circumflex.orm.Predicate.toAggregateHelper
import ru.circumflex.orm.Projection
import ru.circumflex.orm.RelationNode
import ru.circumflex.orm.RelationNode.toRelation
import ru.circumflex.orm.SELECT
import ru.circumflex.orm.str2expr
import ru.circumflex.orm.COUNT
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

  def save(implicit conn: Connection): Comment = {
    val entity = CommentEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
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

  override def invalidateCache(comment: Comment)(implicit session: RWSession) = {
    comment.permissions match {
      case CommentPermissions.PUBLIC =>
        commentCountCache.remove(CommentCountUriIdKey(comment.uriId))
      case CommentPermissions.MESSAGE =>
        inject[CommentRecipientRepo].getByComment(comment.id.get) foreach { cr =>
          cr.userId match {
            case Some(user) => messageWithChildrenCountCache.remove(MessageWithChildrenCountUriIdUserIdKey(comment.uriId, user))
            case None =>
          }
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
}

object CommentCxRepo {

  def all(implicit conn: Connection): Seq[Comment] =
    CommentEntity.all.map(_.view)

  def all(permissions: State[CommentPermission])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map {c => SELECT (c.*) FROM c WHERE (c.permissions EQ permissions) list} map (_.view)

  def all(permissions: State[CommentPermission], userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map {c => SELECT (c.*) FROM c WHERE ((c.userId EQ userId) AND (c.permissions EQ permissions)) list} map (_.view)

  def get(id: Id[Comment])(implicit conn: Connection): Comment =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[Comment])(implicit conn: Connection): Option[Comment] =
    CommentEntity.get(id).map(_.view)

  def get(id: ExternalId[Comment])(implicit conn: Connection): Comment =
    (CommentEntity AS "c").map {c => SELECT (c.*) FROM c WHERE (c.externalId EQ id) unique}
      .map(_.view).getOrElse(throw NotFoundException(id))

  def getRecipients(commentId: Id[Comment])(implicit conn: Connection) =
    CommentRecipientCxRepo.getByComment(commentId)

  def getParticipantsUserIds(comment: Comment)(implicit conn: Connection): Set[Id[User]] = {
    val head = comment.parent map (CommentCxRepo.get(_)) getOrElse(comment)
    (CommentRecipientCxRepo.getByComment(head.id.get) map (_.userId)).flatten.toSet + head.userId
  }
  //slicked
  def getPublicCount(uriId: Id[NormalizedURI])(implicit conn: Connection): Int =
    selectPublic({c => COUNT(c.id)}, uriId).unique.get.intValue
  //slicked
  private def selectPublic[T](
      project: RelationNode[Id[Comment],CommentEntity] => Projection[T],
      uriId: Id[NormalizedURI])(implicit conn: Connection) = {
    val c = CommentEntity AS "c"
    SELECT (project(c)) FROM c WHERE (
        (c.uriId EQ uriId) AND
        (c.permissions EQ CommentPermissions.PUBLIC) AND
        (c.state EQ CommentStates.ACTIVE) AND
        (c.parent IS_NULL))
  }
  //slicked
  def getPrivate(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    selectPrivate({c => c.*}, uriId, userId).list.map(_.view)
  //slicked
  def getPrivateCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Long =
    selectPrivate({c => COUNT(c.id)}, uriId, userId).unique.get
  //slicked
  private def selectPrivate[T](
      project: RelationNode[Id[Comment],CommentEntity] => Projection[T],
      uriId: Id[NormalizedURI],
      userId: Id[User])(implicit conn: Connection) = {
    val c = CommentEntity AS "c"
    SELECT (project(c)) FROM c WHERE (
        (c.uriId EQ uriId) AND
        (c.userId EQ userId) AND
        (c.permissions EQ CommentPermissions.PRIVATE))
  }

  def getMessages(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Seq[Comment] =
    selectMessages({c => c.*}, uriId, userId).list.map(_.view)

  def getMessagesWithChildrenCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Int = {
    val comments = selectMessages({c => c.*}, uriId, userId).list.map(_.view).toSet
    val childrenCounts: Seq[Int] = (comments.toList map {c => getChildCount(c.id.get).toInt})
    childrenCounts.foldLeft(0)((sum, count) => sum + count) + comments.size
  }

  def getMessageCount(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Int =
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
        (c.permissions EQ CommentPermissions.MESSAGE) AND
        (c.parent IS_NULL)))
    .UNION (SELECT (project(c)) FROM c WHERE (
        (c.uriId EQ uriId) AND
        (c.userId EQ userId) AND
        (c.permissions EQ CommentPermissions.MESSAGE) AND
        (c.parent IS_NULL)))
  }

  def getChildren(commentId: Id[Comment])(implicit conn: Connection): Seq[Comment] =
    selectChildren({c => c.*}, commentId).list.map(_.view)

  def getChildCount(commentId: Id[Comment])(implicit conn: Connection): Int =
    selectChildren({c => COUNT(c.id)}, commentId).unique.get.toInt

  private def selectChildren[T](
      project: RelationNode[Id[Comment],CommentEntity] => Projection[T],
      commentId: Id[Comment])(implicit conn: Connection) = {
    val c = CommentEntity AS "c"
    SELECT (project(c)) FROM c WHERE (c.parent EQ commentId)
  }

  def page(page: Int = 0, size: Int = 20, permissions: State[CommentPermission] = CommentPermissions.PUBLIC)(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "c").map { c => SELECT (c.*) FROM c  WHERE (c.permissions EQ permissions)  LIMIT size OFFSET (page * size) ORDER_BY (c.id DESC) list }.map(_.view)

  def count( permissions: State[CommentPermission] = CommentPermissions.PUBLIC)(implicit conn: Connection): Int =
    (CommentEntity AS "c").map(c => SELECT(COUNT(c.id)).FROM(c).WHERE (c.permissions EQ permissions).unique).get.toInt

  def getByUrlId(urlId: Id[URL])(implicit conn: Connection): Seq[Comment] =
    (CommentEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.urlId EQ urlId) list() }.map(_.view)

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

private[model] class CommentEntity extends Entity[Comment, CommentEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[Comment].NOT_NULL(ExternalId())
  val uriId = "normalized_uri_id".ID[NormalizedURI].NOT_NULL
  val urlId = "url_id".ID[URL]
  val userId = "user_id".ID[User]
  val pageTitle = "page_title".VARCHAR(1024).NOT_NULL
  val text = "text".CLOB.NOT_NULL
  val parent = "parent".ID[Comment]
  val permissions = "permissions".STATE[CommentPermission].NOT_NULL(CommentPermissions.PUBLIC)
  val state = "state".STATE[Comment].NOT_NULL(CommentStates.ACTIVE)

  def relation = CommentEntity

  def view(implicit conn: Connection): Comment = Comment(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    uriId = uriId(),
    urlId = urlId.value,
    userId = userId(),
    pageTitle = pageTitle(),
    text = LargeString(text()),
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
    comment.urlId.set(view.urlId)
    comment.userId := view.userId
    comment.pageTitle := view.pageTitle
    comment.text := view.text
    comment.parent.set(view.parent)
    comment.permissions := view.permissions
    comment.state := view.state
    comment
  }
}
