package com.keepit.model

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
import ru.circumflex.orm._
import java.net.URI
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import com.google.inject.{Inject, ImplementedBy, Singleton}

case class BookmarkSource(value: String) {
  implicit def getValue = value
  implicit def source(value: String) = BookmarkSource(value)
  override def toString = value
}

object BookmarkSource {
  implicit def source(value: String) = BookmarkSource(value)
}

case class Bookmark(
  id: Option[Id[Bookmark]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Bookmark] = ExternalId(),
  title: String,
  uriId: Id[NormalizedURI],
  urlId: Option[Id[URL]] = None, // todo(Andrew): remove Option after grandfathering process
  url: String, // denormalized for efficiency
  bookmarkPath: Option[String] = None,
  isPrivate: Boolean = false,
  userId: Id[User],
  state: State[Bookmark] = BookmarkStates.ACTIVE,
  source: BookmarkSource,
  kifiInstallation: Option[ExternalId[KifiInstallation]] = None
) extends ModelWithExternalId[Bookmark] {
  def withId(id: Id[Bookmark]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def withPrivate(isPrivate: Boolean) = copy(isPrivate = isPrivate)

  def withActive(isActive: Boolean) = copy(state = isActive match {
    case true => BookmarkStates.ACTIVE
    case false => BookmarkStates.INACTIVE
  })

  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)

  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))

  def isActive: Boolean = state == BookmarkStates.ACTIVE

  def save(implicit conn: Connection): Bookmark = {
    val entity = BookmarkEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

  def delete()(implicit conn: Connection): Unit = {
    val res = (BookmarkEntity AS "b").map { b => DELETE (b) WHERE (b.id EQ this.id.get) execute }
    if (res != 1) {
      throw new Exception("[%s] did not delete %s".format(res, this))
    }
  }
}

@ImplementedBy(classOf[BookmarkRepoImpl])
trait BookmarkRepo extends Repo[Bookmark] with ExternalIdColumnFunction[Bookmark] {
  def allActive()(implicit session: RSession): Seq[Bookmark]
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Bookmark]
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Bookmark]
  def count(userId: Id[User])(implicit session: RSession): Int
  def getCountByInstallation(kifiInstallation: ExternalId[KifiInstallation])(implicit session: RSession): Int
}

@Singleton
class BookmarkRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[Bookmark] with BookmarkRepo with ExternalIdColumnDbFunction[Bookmark] {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.TypeMapper._
  import org.scalaquery.ql.TypeMapperDelegate._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[Bookmark](db, "bookmark") with ExternalIdColumn[Bookmark] {
    def title = column[String]("title", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def url =   column[String]("url", O.NotNull)
    def state = column[State[Bookmark]]("state", O.NotNull)
    def bookmarkPath = column[String]("bookmark_path", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def source = column[BookmarkSource]("source", O.NotNull)
    def kifiInstallation = column[ExternalId[KifiInstallation]]("kifi_installation", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title ~ uriId ~ urlId.? ~ url ~ bookmarkPath.? ~ isPrivate ~ userId ~ state ~ source ~ kifiInstallation.? <> (Bookmark, Bookmark.unapply _)
  }

  def allActive()(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.state === BookmarkStates.ACTIVE) yield b).list

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.userId === userId && b.state === BookmarkStates.ACTIVE) yield b).firstOption

  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.state === BookmarkStates.ACTIVE) yield b).list

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE) yield b).list

  def count(userId: Id[User])(implicit session: RSession): Int =
    Query(table.where(b => b.userId === userId).count).first

  def getCountByInstallation(kifiInstallation: ExternalId[KifiInstallation])(implicit session: RSession): Int =
    Query(table.where(b => b.kifiInstallation === kifiInstallation).count).first
}

object BookmarkFactory {

  def apply(uri: NormalizedURI, userId: Id[User], title: String, url: URL, source: BookmarkSource, isPrivate: Boolean, kifiInstallation: Option[ExternalId[KifiInstallation]]): Bookmark =
    Bookmark(title = title, userId = userId, uriId = uri.id.get, urlId = Some(url.id.get), url = url.url, source = source, isPrivate = isPrivate)

  def apply(title: String, url: URL, uriId: Id[NormalizedURI], userId: Id[User], source: BookmarkSource): Bookmark =
    Bookmark(title = title, urlId = Some(url.id.get), url = url.url, uriId = uriId, userId = userId, source = source)

  def apply(title: String, urlId: Id[URL],  uriId: Id[NormalizedURI], userId: Id[User], source: BookmarkSource, isPrivate: Boolean): Bookmark =
    BookmarkFactory(title = title, urlId = urlId, uriId = uriId, userId = userId, source = source, isPrivate = isPrivate)
}

//slicked!
object BookmarkCxRepo {
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit conn: Connection): Option[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.userId EQ userId AND (b.uriId EQ uriId)) LIMIT(1) unique }.map(_.view)

  def ofUri(uri: NormalizedURI)(implicit conn: Connection): Seq[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.uriId EQ uri.id.get) list }.map(_.view)

  def ofUser(user: User)(implicit conn: Connection): Seq[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.userId EQ user.id.get) list }.map(_.view)

  def count(user: User)(implicit conn: Connection): Long =
    (BookmarkEntity AS "b").map(b => SELECT(COUNT(b.id)).FROM(b).WHERE(b.userId EQ user.id.get).unique).get

  def all(implicit conn: Connection): Seq[Bookmark] =
    BookmarkEntity.all.map(_.view)

  def count(implicit conn: Connection): Long =
    (BookmarkEntity AS "b").map(b => SELECT(COUNT(b.id)).FROM(b).unique).get

  def page(page: Int = 0, size: Int = 20)(implicit conn: Connection): Seq[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b LIMIT size OFFSET (page * size) ORDER_BY (b.id DESC) list }.map(_.view)

  def get(id: Id[Bookmark])(implicit conn: Connection): Bookmark =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[Bookmark])(implicit conn: Connection): Option[Bookmark] =
    BookmarkEntity.get(id).map(_.view)

  def get(externalId: ExternalId[Bookmark])(implicit conn: Connection): Bookmark =
    getOpt(externalId).getOrElse(throw NotFoundException(externalId))

  def getOpt(externalId: ExternalId[Bookmark])(implicit conn: Connection): Option[Bookmark] =
    (BookmarkEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.externalId EQ externalId) unique }.map(_.view)

  def getCountByInstallation(installation: ExternalId[KifiInstallation])(implicit conn: Connection): Long =
    (BookmarkEntity AS "b").map { b => SELECT (COUNT(b.*)) FROM b WHERE (b.kifiInstallation EQ installation) unique } getOrElse(0)
}

object BookmarkStates {
  val ACTIVE = State[Bookmark]("active")
  val INACTIVE = State[Bookmark]("inactive")
}

private[model] class BookmarkEntity extends Entity[Bookmark, BookmarkEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[Bookmark].NOT_NULL(ExternalId())
  val title = "title".VARCHAR(256).NOT_NULL
  val uriId = "uri_id".ID[NormalizedURI].NOT_NULL
  val urlId = "url_id".ID[URL]
  val url = "url".VARCHAR(256).NOT_NULL
  val state = "state".STATE[Bookmark].NOT_NULL(BookmarkStates.ACTIVE)
  val bookmarkPath = "bookmark_path".VARCHAR(512).NOT_NULL
  val userId = "user_id".ID[User]
  val isPrivate = "is_private".BOOLEAN.NOT_NULL
  val source = "source".VARCHAR(256).NOT_NULL
  val kifiInstallation = "kifi_installation".EXTERNAL_ID[KifiInstallation]

  def relation = BookmarkEntity

  def view(implicit conn: Connection): Bookmark = Bookmark(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    title = title(),
    state = state(),
    uriId = uriId(),
    urlId = urlId.value,
    url = url(),
    isPrivate = isPrivate(),
    userId = userId(),
    bookmarkPath = bookmarkPath.value,
    source = source(),
    kifiInstallation = kifiInstallation.value
  )
}

private[model] object BookmarkEntity extends BookmarkEntity with EntityTable[Bookmark, BookmarkEntity] {
  override def relationName = "bookmark"

  def apply(view: Bookmark): BookmarkEntity = {
    val bookmark = new BookmarkEntity
    bookmark.id.set(view.id)
    bookmark.createdAt := view.createdAt
    bookmark.updatedAt := view.updatedAt
    bookmark.externalId := view.externalId
    bookmark.title := view.title
    bookmark.state := view.state
    bookmark.uriId := view.uriId
    bookmark.urlId.set(view.urlId)
    bookmark.url := view.url
    bookmark.bookmarkPath.set(view.bookmarkPath)
    bookmark.isPrivate := view.isPrivate
    bookmark.userId.set(view.userId)
    bookmark.source := view.source.value
    bookmark.kifiInstallation.set(view.kifiInstallation)
    bookmark
  }
}


