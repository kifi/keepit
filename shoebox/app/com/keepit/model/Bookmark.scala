package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

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
  title: Option[String] = None,
  uriId: Id[NormalizedURI],
  urlId: Option[Id[URL]] = None, // todo(Andrew): remove Option after grandfathering process
  url: String, // denormalized for efficiency
  bookmarkPath: Option[String] = None,
  isPrivate: Boolean = false,
  userId: Id[User],
  state: State[Bookmark] = BookmarkStates.ACTIVE,
  source: BookmarkSource,
  kifiInstallation: Option[ExternalId[KifiInstallation]] = None,
  seq: SequenceNumber = SequenceNumber.ZERO
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

object Bookmark {
  implicit def bookmarkFormat = (
    (__ \ 'id).formatNullable(Id.format[Bookmark]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'externalId).format(ExternalId.format[Bookmark]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'urlId).formatNullable(Id.format[URL]) and
    (__ \ 'url).format[String] and
    (__ \ 'bookmarkPath).formatNullable[String] and
    (__ \ 'isPrivate).format[Boolean] and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'state).format(State.format[Bookmark]) and
    (__ \ 'source).format[String].inmap(BookmarkSource.apply, unlift(BookmarkSource.unapply)) and
    (__ \ 'kifiInstallation).formatNullable(ExternalId.format[KifiInstallation]) and
    (__ \ 'seq).format(SequenceNumber.sequenceNumberFormat)
  )(Bookmark.apply, unlift(Bookmark.unapply))
}

@ImplementedBy(classOf[BookmarkRepoImpl])
trait BookmarkRepo extends Repo[Bookmark] with ExternalIdColumnFunction[Bookmark] {
  def allActive()(implicit session: RSession): Seq[Bookmark]
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User],
      excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))
      (implicit session: RSession): Option[Bookmark]
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark]
  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User], lastExternalId: Option[ExternalId[Bookmark]], count: Int)
      (implicit session: RSession): Seq[Bookmark]
  def getCountByUser(userId: Id[User])(implicit session: RSession): Int
  def getUsersChanged(num: SequenceNumber)(implicit session: RSession): Seq[(Id[User], SequenceNumber)]
  def getCountByInstallation(kifiInstallation: ExternalId[KifiInstallation])(implicit session: RSession): Int
  def getNumMutual(userId: Id[User], otherUserId: Id[User])(implicit session: RSession): Int
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark]
  def delete(id: Id[Bookmark])(implicit sesion: RSession): Unit
  def save(model: Bookmark)(implicit session: RWSession): Bookmark
}

case class BookmarkCountKey() extends Key[Int] {
  val namespace = "bookmark_count"
  def toKey(): String = "k"
}

class BookmarkCountCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[BookmarkCountKey, Int] {
  val ttl = 1 hours
  def deserialize(obj: Any): Int = obj.asInstanceOf[Int]
  def serialize(count: Int) = count
}

@Singleton
class BookmarkRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val countCache: BookmarkCountCache)
      extends DbRepo[Bookmark] with BookmarkRepo with ExternalIdColumnDbFunction[Bookmark] {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import scala.slick.lifted.Query

  private val sequence = db.getSequence("bookmark_sequence")

  override val table = new RepoTable[Bookmark](db, "bookmark") with ExternalIdColumn[Bookmark] {
    def title = column[Option[String]]("title", O.Nullable)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def url =   column[String]("url", O.NotNull)
    def bookmarkPath = column[String]("bookmark_path", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def source = column[BookmarkSource]("source", O.NotNull)
    def kifiInstallation = column[ExternalId[KifiInstallation]]("kifi_installation", O.Nullable)
    def seq = column[SequenceNumber]("seq", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title ~ uriId ~ urlId.? ~ url ~ bookmarkPath.? ~ isPrivate ~
        userId ~ state ~ source ~ kifiInstallation.? ~ seq <> (Bookmark.apply _, Bookmark.unapply _)
  }

  override def invalidateCache(bookmark: Bookmark)(implicit session: RSession) = {
    countCache.remove(BookmarkCountKey())
    bookmark
  }

  override def count(implicit session: RSession): Int = {
    countCache.getOrElse(BookmarkCountKey()) {
      super.count
    }
  }

  def allActive()(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.state === BookmarkStates.ACTIVE) yield b).list

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User],
      excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))
      (implicit session: RSession): Option[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.userId === userId && b.state =!= excludeState.getOrElse(null)) yield b)
      .sortBy(_.state === BookmarkStates.INACTIVE).firstOption

  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.state === BookmarkStates.ACTIVE) yield b).list

  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.state === BookmarkStates.ACTIVE && b.title.isNull) yield b).list

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE) yield b).list

  def getByUser(userId: Id[User], beforeExternalId: Option[ExternalId[Bookmark]], count: Int)
      (implicit session: RSession): Seq[Bookmark] = {
    val q = if (beforeExternalId.isDefined) {
      val last = get(beforeExternalId.get)
      for (b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE &&
          (b.createdAt < last.createdAt || b.id < last.id.get && b.createdAt === last.createdAt)) yield b
    } else {
      for (b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE) yield b
    }
    q.sortBy(_.id desc).sortBy(_.createdAt desc).take(count).list
  }

  def getCountByUser(userId: Id[User])(implicit session: RSession): Int =
    Query((for(b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE) yield b).length).first

  def getUsersChanged(num: SequenceNumber)(implicit session: RSession): Seq[(Id[User], SequenceNumber)] =
    (for (b <- table if b.seq > num) yield b).groupBy(_.userId).map{ case (u, b) => (u -> b.map(_.seq).max.get) }.sortBy(_._2).list

  def getCountByInstallation(kifiInstallation: ExternalId[KifiInstallation])(implicit session: RSession): Int =
    Query(table.where(b => b.kifiInstallation === kifiInstallation).length).first

  def getNumMutual(userId: Id[User], otherUserId: Id[User])(implicit session: RSession): Int =
    Query((for {
      b1 <- table if b1.userId === userId && b1.state === BookmarkStates.ACTIVE
      b2 <- table if b2.userId === otherUserId && b2.state === BookmarkStates.ACTIVE && b2.uriId === b1.uriId && !b2.isPrivate
    } yield b2.id).countDistinct).first

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.urlId === urlId) yield b).list

  override def save(model: Bookmark)(implicit session: RWSession) = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    super.save(newModel)
  }

  def delete(id: Id[Bookmark])(implicit sesion: RSession): Unit = (for(b <- table if b.id === id) yield b).delete
}

object BookmarkFactory {

  def apply(uri: NormalizedURI, userId: Id[User], title: Option[String], url: URL, source: BookmarkSource, isPrivate: Boolean, kifiInstallation: Option[ExternalId[KifiInstallation]]): Bookmark =
    Bookmark(title = title, userId = userId, uriId = uri.id.get, urlId = Some(url.id.get), url = url.url, source = source, isPrivate = isPrivate)

  def apply(title: String, url: URL, uriId: Id[NormalizedURI], userId: Id[User], source: BookmarkSource): Bookmark =
    Bookmark(title = Some(title), urlId = Some(url.id.get), url = url.url, uriId = uriId, userId = userId, source = source)

  def apply(title: String, urlId: Id[URL],  uriId: Id[NormalizedURI], userId: Id[User], source: BookmarkSource, isPrivate: Boolean): Bookmark =
    BookmarkFactory(title = title, urlId = urlId, uriId = uriId, userId = userId, source = source, isPrivate = isPrivate)
}

object BookmarkStates extends States[Bookmark]
