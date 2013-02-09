package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
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
import java.net.URI
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64

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
}

@ImplementedBy(classOf[BookmarkRepoImpl])
trait BookmarkRepo extends Repo[Bookmark] with ExternalIdColumnFunction[Bookmark] {
  def allActive()(implicit session: RSession): Seq[Bookmark]
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Bookmark]
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Bookmark]
  def count(userId: Id[User])(implicit session: RSession): Int
  def getCountByInstallation(kifiInstallation: ExternalId[KifiInstallation])(implicit session: RSession): Int
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark]
  def uriStats(uri: NormalizedURI)(implicit sesion: RSession): NormalizedURIStats
  def delete(id: Id[Bookmark])(implicit sesion: RSession): Unit
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

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.urlId === urlId) yield b).list

  def uriStats(uri: NormalizedURI)(implicit sesion: RSession): NormalizedURIStats = 
    NormalizedURIStats(uri, getByUri(uri.id.get))

  def delete(id: Id[Bookmark])(implicit sesion: RSession): Unit = (for(b <- table if b.id === id) yield b).delete
}

object BookmarkFactory {

  def apply(uri: NormalizedURI, userId: Id[User], title: String, url: URL, source: BookmarkSource, isPrivate: Boolean, kifiInstallation: Option[ExternalId[KifiInstallation]]): Bookmark =
    Bookmark(title = title, userId = userId, uriId = uri.id.get, urlId = Some(url.id.get), url = url.url, source = source, isPrivate = isPrivate)

  def apply(title: String, url: URL, uriId: Id[NormalizedURI], userId: Id[User], source: BookmarkSource): Bookmark =
    Bookmark(title = title, urlId = Some(url.id.get), url = url.url, uriId = uriId, userId = userId, source = source)

  def apply(title: String, urlId: Id[URL],  uriId: Id[NormalizedURI], userId: Id[User], source: BookmarkSource, isPrivate: Boolean): Bookmark =
    BookmarkFactory(title = title, urlId = urlId, uriId = uriId, userId = userId, source = source, isPrivate = isPrivate)
}

object BookmarkStates extends States[Bookmark]
