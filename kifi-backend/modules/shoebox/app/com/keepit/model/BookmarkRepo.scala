package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.{SequenceNumber, ExternalId, State, Id}
import com.keepit.common.time.Clock
import org.joda.time.DateTime
import scala.Some
import com.keepit.common.mail.ElectronicMail

@ImplementedBy(classOf[BookmarkRepoImpl])
trait BookmarkRepo extends Repo[Bookmark] with ExternalIdColumnFunction[Bookmark] {
  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Bookmark]])(implicit session: RSession): Seq[Bookmark]
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User],
                      excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))
                     (implicit session: RSession): Option[Bookmark]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark]
  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Bookmark]], afterId: Option[ExternalId[Bookmark]],
                collectionId: Option[Id[Collection]], count: Int)(implicit session: RSession): Seq[Bookmark]
  def getCountByUser(userId: Id[User], includePrivate: Boolean = true)(implicit session: RSession): Int
  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int
  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: BookmarkSource)(implicit session: RSession): Int
  def getBookmarksChanged(num: SequenceNumber, fetchSize: Int)(implicit session: RSession): Seq[Bookmark]
  def getNumMutual(userId: Id[User], otherUserId: Id[User])(implicit session: RSession): Int
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark]
  def delete(id: Id[Bookmark])(implicit session: RSession): Unit
  def save(model: Bookmark)(implicit session: RWSession): Bookmark
  def detectDuplicates()(implicit session: RSession): Seq[(Id[User], Id[NormalizedURI])]
  def removeFromCache(bookmark: Bookmark)(implicit session: RSession): Unit
  def latestBookmark(uriId: Id[NormalizedURI])(implicit session: RSession): Option[Bookmark]
  def getByTitle(title: String)(implicit session: RSession): Seq[Bookmark]
  def exists(uriId: Id[NormalizedURI], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Boolean
}

@Singleton
class BookmarkRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val countCache: BookmarkCountCache,
  val keepToCollectionRepo: KeepToCollectionRepoImpl,
  collectionRepo: CollectionRepo,
  bookmarkUriUserCache: BookmarkUriUserCache,
  latestBookmarkUriCache: LatestBookmarkUriCache)
  extends DbRepo[Bookmark] with BookmarkRepo with ExternalIdColumnDbFunction[Bookmark] {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import scala.slick.lifted.Query

  private val sequence = db.getSequence("bookmark_sequence")

  override val table = new RepoTable[Bookmark](db, "bookmark") with ExternalIdColumn[Bookmark] {
    def title = column[Option[String]]("title", O.Nullable)//indexd
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)//indexd
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def url =   column[String]("url", O.NotNull)//indexd
    def bookmarkPath = column[String]("bookmark_path", O.Nullable)
    def userId = column[Id[User]]("user_id", O.Nullable)//indexd
    def isPrivate = column[Boolean]("is_private", O.NotNull)//indexd
    def source = column[BookmarkSource]("source", O.NotNull)
    def kifiInstallation = column[ExternalId[KifiInstallation]]("kifi_installation", O.Nullable)
    def seq = column[SequenceNumber]("seq", O.Nullable)//indexd
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title ~ uriId ~ urlId.? ~ url ~ bookmarkPath.? ~ isPrivate ~
      userId ~ state ~ source ~ kifiInstallation.? ~ seq <> (Bookmark.apply _, Bookmark.unapply _)
  }

  override def save(model: Bookmark)(implicit session: RWSession) = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    for (bid <- model.id; cid <- keepToCollectionRepo.getCollectionsForBookmark(bid))
      collectionRepo.collectionChanged(cid)
    super.save(newModel.clean())
  }

  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Bookmark]])(implicit session: RSession): Seq[Bookmark] =  {
    val q = for {
      t <- table if (t.isPrivate === false || includePrivate == true) && !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def removeFromCache(bookmark: Bookmark)(implicit session: RSession): Unit = {
    bookmarkUriUserCache.remove(BookmarkUriUserKey(bookmark.uriId, bookmark.userId))
    countCache.remove(BookmarkCountKey(Some(bookmark.userId)))
  }

  override def invalidateCache(bookmark: Bookmark)(implicit session: RSession) = {
    bookmarkUriUserCache.set(BookmarkUriUserKey(bookmark.uriId, bookmark.userId), bookmark)
    countCache.remove(BookmarkCountKey(Some(bookmark.userId)))
    latestBookmarkUriCache.set(LatestBookmarkUriKey(bookmark.uriId), bookmark)
    bookmark
  }

  override def count(implicit session: RSession): Int = {
    countCache.getOrElse(BookmarkCountKey()) {
      super.count
    }
  }

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User],
                      excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))
                     (implicit session: RSession): Option[Bookmark] =
    bookmarkUriUserCache.getOrElseOpt(BookmarkUriUserKey(uriId, userId)) {
      val bookmarks = (for(b <- table if b.uriId === uriId && b.userId === userId) yield b).list
      assert(bookmarks.length <= 1, s"${bookmarks.length} bookmarks found for (uri, user) pair ${(uriId, userId)}")
      bookmarks.headOption
    }.filter { b => excludeState.map(_ != b.state).getOrElse(true) }

  def getByTitle(title: String)(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.title === title) yield b).list

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).list

  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.state === BookmarkStates.ACTIVE && b.title.isNull) yield b).list

  def getByUser(userId: Id[User], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt).list

  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Bookmark]], afterId: Option[ExternalId[Bookmark]],
                collectionId: Option[Id[Collection]], count: Int)(implicit session: RSession): Seq[Bookmark] = {
    import keepToCollectionRepo.{stateTypeMapper => ktcStateMapper}
    val (maybeBefore, maybeAfter) = (beforeId map get, afterId map get)
    val q = (for {
      b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE &&
      (maybeBefore.map { before =>
        b.createdAt < before.createdAt || b.id < before.id.get && b.createdAt === before.createdAt
      } getOrElse (b.id === b.id)) &&
      (maybeAfter.map { after =>
        b.createdAt > after.createdAt || b.id > after.id.get && b.createdAt === after.createdAt
      } getOrElse (b.id === b.id))
    } yield b).sortBy(_.createdAt)
    (collectionId.map { cid =>
      for {
        (b, ktc) <- q join keepToCollectionRepo.table on (_.id === _.bookmarkId)
        if ktc.collectionId === cid && ktc.state === KeepToCollectionStates.ACTIVE
      } yield b
    } getOrElse q).sortBy(_.id desc).sortBy(_.createdAt desc).take(count).list
  }

  def getCountByUser(userId: Id[User], includePrivate: Boolean)(implicit session: RSession): Int =
    if (includePrivate) {
      countCache.getOrElse(BookmarkCountKey(Some(userId))) {
        Query((for(b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE) yield b).length).first
      }
    } else Query((for(b <- table if b.userId === userId && b.state === BookmarkStates.ACTIVE && !b.isPrivate) yield b).length).first

  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int =
    Query((for(b <- table if b.updatedAt >= from && b.updatedAt <= to && b.state === BookmarkStates.ACTIVE) yield b).length).first

  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: BookmarkSource)(implicit session: RSession): Int =
    Query((for(b <- table if b.updatedAt >= from && b.updatedAt <= to && b.state === BookmarkStates.ACTIVE && b.source === source) yield b).length).first

  def getBookmarksChanged(num: SequenceNumber, limit: Int)(implicit session: RSession): Seq[Bookmark] =
    (for (b <- table if b.seq > num) yield b).sortBy(_.seq).take(limit).list

  def getNumMutual(userId: Id[User], otherUserId: Id[User])(implicit session: RSession): Int =
    Query((for {
      b1 <- table if b1.userId === userId && b1.state === BookmarkStates.ACTIVE
      b2 <- table if b2.userId === otherUserId && b2.state === BookmarkStates.ACTIVE && b2.uriId === b1.uriId && !b2.isPrivate
    } yield b2.id).countDistinct).first

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.urlId === urlId) yield b).list

  def delete(id: Id[Bookmark])(implicit sesion: RSession): Unit = {
    val q = (for(b <- table if b.id === id) yield b)
    q.firstOption.map{ bm => removeFromCache(bm) }
    q.delete
  }

  def detectDuplicates()(implicit session: RSession): Seq[(Id[User], Id[NormalizedURI])] = {
    val q = for {
      r <- table
      s <- table if (r.userId === s.userId && r.uriId === s.uriId && r.id < s.id)
    } yield (r.userId, r.uriId)
    q.list.distinct
  }

  def latestBookmark(uriId: Id[NormalizedURI])(implicit session: RSession): Option[Bookmark] = {
    latestBookmarkUriCache.getOrElseOpt(LatestBookmarkUriKey(uriId)) {
      val bookmarks = for { bookmark <- table if bookmark.uriId === uriId } yield bookmark
      val max = bookmarks.map(_.updatedAt).max
      val latest = for { bookmark <- bookmarks if bookmark.updatedAt >= max } yield bookmark
      latest.sortBy(_.updatedAt desc).firstOption
    }
  }

  def exists(uriId: Id[NormalizedURI], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Boolean = {
    (for(b <- table if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).firstOption.isDefined
  }
}
