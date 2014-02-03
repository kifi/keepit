package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.{SequenceNumber, ExternalId, State, Id}
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.slick.jdbc.{GetResult, StaticQuery}
import com.keepit.common.logging.Logging

@ImplementedBy(classOf[BookmarkRepoImpl])
trait BookmarkRepo extends Repo[Bookmark] with ExternalIdColumnFunction[Bookmark] with SeqNumberFunction[Bookmark] {
  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Bookmark]])(implicit session: RSession): Seq[Bookmark]
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Bookmark]
  def getByUriAndUserAllStates(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Bookmark]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark]
  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark]
  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Bookmark]], afterId: Option[ExternalId[Bookmark]], count: Int)(implicit session: RSession): Seq[Bookmark]
  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Bookmark]], afterId: Option[ExternalId[Bookmark]], count: Int)(implicit session: RSession): Seq[Bookmark]
  def getCountByUser(userId: Id[User], includePrivate: Boolean = true)(implicit session: RSession): Int
  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int)
  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int
  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: BookmarkSource)(implicit session: RSession): Int
  def getBookmarksChanged(num: SequenceNumber, fetchSize: Int)(implicit session: RSession): Seq[Bookmark]
  def getNumMutual(userId: Id[User], otherUserId: Id[User])(implicit session: RSession): Int
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark]
  def delete(id: Id[Bookmark])(implicit session: RWSession): Unit
  def save(model: Bookmark)(implicit session: RWSession): Bookmark
  def detectDuplicates()(implicit session: RSession): Seq[(Id[User], Id[NormalizedURI])]
  def latestBookmark(uriId: Id[NormalizedURI])(implicit session: RSession): Option[Bookmark]
  def getByTitle(title: String)(implicit session: RSession): Seq[Bookmark]
  def exists(uriId: Id[NormalizedURI], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Boolean
  def getSourcesByUser()(implicit session: RSession) : Map[Id[User], Seq[BookmarkSource]]
  def oldestBookmark(userId: Id[User], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Option[Bookmark]
}

@Singleton
class BookmarkRepoImpl @Inject() (
   val db: DataBaseComponent,
   val clock: Clock,
   val countCache: BookmarkCountCache,
   val keepToCollectionRepo: KeepToCollectionRepoImpl,
   collectionRepo: CollectionRepo,
   bookmarkUriUserCache: BookmarkUriUserCache,
   latestBookmarkUriCache: LatestBookmarkUriCache
) extends DbRepo[Bookmark] with BookmarkRepo with ExternalIdColumnDbFunction[Bookmark] with SeqNumberDbFunction[Bookmark] with Logging {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import scala.slick.lifted.Query

  private val sequence = db.getSequence("bookmark_sequence")

  override val table = new RepoTable[Bookmark](db, "bookmark") with ExternalIdColumn[Bookmark] with NamedColumns with SeqNumberColumn[Bookmark]{
    def title = column[String]("title", O.Nullable)//indexd
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)//indexd
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def url =   column[String]("url", O.NotNull)//indexd
    def bookmarkPath = column[String]("bookmark_path", O.Nullable)
    def userId = column[Id[User]]("user_id", O.Nullable)//indexd
    def isPrivate = column[Boolean]("is_private", O.NotNull)//indexd
    def source = column[BookmarkSource]("source", O.NotNull)
    def kifiInstallation = column[ExternalId[KifiInstallation]]("kifi_installation", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title.? ~ uriId ~ urlId.? ~ url ~ bookmarkPath.? ~ isPrivate ~
        userId ~ state ~ source ~ kifiInstallation.? ~ seq <> (Bookmark.apply _, Bookmark.unapply _)
  }

  private implicit val getBookmarkResult = GetResult(r => table.*.getResult(db.Driver.profile, r))
  private val bookmarkColumnOrder = table.columnStrings("bm")


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

  override def deleteCache(bookmark: Bookmark)(implicit session: RSession): Unit = {
    bookmarkUriUserCache.remove(BookmarkUriUserKey(bookmark.uriId, bookmark.userId))
    countCache.remove(BookmarkCountKey(Some(bookmark.userId)))
    latestBookmarkUriCache.remove(LatestBookmarkUriKey(bookmark.uriId))
  }

  override def invalidateCache(bookmark: Bookmark)(implicit session: RSession): Unit = {
    if (bookmark.state == BookmarkStates.INACTIVE) {
      deleteCache(bookmark)
    } else {
      bookmarkUriUserCache.set(BookmarkUriUserKey(bookmark.uriId, bookmark.userId), bookmark)
      countCache.remove(BookmarkCountKey(Some(bookmark.userId)))
      latestBookmarkUriCache.set(LatestBookmarkUriKey(bookmark.uriId), bookmark)
    }
  }

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Bookmark] =
    bookmarkUriUserCache.getOrElseOpt(BookmarkUriUserKey(uriId, userId)) {
      val bookmarks = (for(b <- table if b.uriId === uriId && b.userId === userId && b.state === BookmarkStates.ACTIVE) yield b).list
      assert(bookmarks.length <= 1, s"${bookmarks.length} bookmarks found for (uri, user) pair ${(uriId, userId)}")
      bookmarks.headOption
    }

  def getByUriAndUserAllStates(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Bookmark] ={
    (for(b <- table if b.uriId === uriId && b.userId === userId ) yield b).firstOption
  }

  def getByTitle(title: String)(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.title === title) yield b).list

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).list

  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.uriId === uriId && b.state === BookmarkStates.ACTIVE && b.title.isNull) yield b).list

  def getByUser(userId: Id[User], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt).list

  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Bookmark]], afterId: Option[ExternalId[Bookmark]], count: Int)(implicit session: RSession): Seq[Bookmark] = {
    import keepToCollectionRepo.{stateTypeMapper => ktcStateMapper}
    import StaticQuery.interpolation
    import scala.collection.JavaConversions._

    // Performance sensitive call.
    // Separate queries for each case because the db will cache the query plans when we only use parametrized queries instead of raw strings.
    val interpolated = (beforeId map get, afterId map get) match {
      case (None, None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId.id} and bm.state = 'active' order by bm.id desc limit $count;"""
      case (None, Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId.id} and bm.state = '#${BookmarkStates.ACTIVE.value}' and bm.id > ${after.id.get.id} order by bm.id desc limit $count;"""
      case (Some(before), None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId.id} and bm.state = '#${BookmarkStates.ACTIVE.value}' and bm.id < ${before.id.get.id} order by bm.id desc limit $count;"""
      case (Some(before), Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId.id} and bm.state = '#${BookmarkStates.ACTIVE.value}' and bm.id < ${before.id.get.id} and bm.id > ${after.id.get.id} order by bm.id desc limit $count;"""
    }
    interpolated.as[Bookmark].list
  }

  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Bookmark]], afterId: Option[ExternalId[Bookmark]], count: Int)(implicit session: RSession): Seq[Bookmark] = {
    import keepToCollectionRepo.{stateTypeMapper => ktcStateMapper}
    import StaticQuery.interpolation

    // Performance sensitive call.
    // Separate queries for each case because the db will cache the query plans when we only use parametrized queries.
    val interpolated = (beforeId map get, afterId map get) match {
      case (None, None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId.id} and bm.user_id = ${userId.id} and bm.state = '#${BookmarkStates.ACTIVE.value}'
                and kc.state='#${KeepToCollectionStates.ACTIVE.value}' order by bm.id desc limit $count;"""
      case (None, Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId.id} and bm.user_id = ${userId.id} and bm.state = '#${BookmarkStates.ACTIVE.value}'
                and kc.state='#${KeepToCollectionStates.ACTIVE.value}' and bm.id > ${after.id.get.id} order by bm.id desc limit $count;"""
      case (Some(before), None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId.id} and bm.user_id = ${userId.id} and bm.state = '#${BookmarkStates.ACTIVE.value}'
                and kc.state='#${KeepToCollectionStates.ACTIVE.value}' and bm.id < ${before.id.get.id} order by bm.id desc limit $count;"""
      case (Some(before), Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId.id} and bm.user_id = ${userId.id} and bm.state = '#${BookmarkStates.ACTIVE.value}'
                and kc.state='#${KeepToCollectionStates.ACTIVE.value}' and bm.id < ${before.id.get.id} and bm.id > ${after.id.get.id} order by bm.id desc limit $count;"""
    }
    interpolated.as[Bookmark].list
  }

  def getCountByUser(userId: Id[User], includePrivate: Boolean)(implicit session: RSession): Int =
    if (includePrivate) {
      countCache.getOrElse(BookmarkCountKey(Some(userId))) {
        val sql = s"select count(*) from bookmark where user_id=${userId.id} and state = '${BookmarkStates.ACTIVE.value}'"
        StaticQuery.queryNA[Int](sql).first()
      }
    } else {
      val sql = s"select count(*) from bookmark where user_id=${userId.id} and state = '${BookmarkStates.ACTIVE.value}' and is_private = ${includePrivate.toString}"
      StaticQuery.queryNA[Int](sql).first()
    }

  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    val privateCount = StaticQuery.queryNA[Int](s"select count(*) from bookmark where user_id=${userId.id} and state = '${BookmarkStates.ACTIVE.value}' and is_private = true").first()
    val publicCount = StaticQuery.queryNA[Int](s"select count(*) from bookmark where user_id=${userId.id} and state = '${BookmarkStates.ACTIVE.value}' and is_private = false").first()
    (privateCount, publicCount)
  }

  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int = {
    val sql = s"select count(*) from bookmark where updated_at between '${SQL_DATETIME_FORMAT.print(from)}' and '${SQL_DATETIME_FORMAT.print(to)}' and state='${BookmarkStates.ACTIVE.value}'"
    val q = StaticQuery.queryNA[Int](sql)
    q.first
  }

  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: BookmarkSource)(implicit session: RSession): Int = {
    val sql = s"select count(*) from bookmark where updated_at between '${SQL_DATETIME_FORMAT.print(from)}' and '${SQL_DATETIME_FORMAT.print(to)}' and state='${BookmarkStates.ACTIVE.value}' and source='${source.value}'"
    val q = StaticQuery.queryNA[Int](sql)
    q.first
  }

  def getBookmarksChanged(num: SequenceNumber, limit: Int)(implicit session: RSession): Seq[Bookmark] = super.getBySequenceNumber(num, limit)

  def getNumMutual(userId: Id[User], otherUserId: Id[User])(implicit session: RSession): Int =
    Query((for {
      b1 <- table if b1.userId === userId && b1.state === BookmarkStates.ACTIVE
      b2 <- table if b2.userId === otherUserId && b2.state === BookmarkStates.ACTIVE && b2.uriId === b1.uriId && !b2.isPrivate
    } yield b2.id).countDistinct).first

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Bookmark] =
    (for(b <- table if b.urlId === urlId) yield b).list

  def delete(id: Id[Bookmark])(implicit sesion: RWSession): Unit = {
    val q = (for(b <- table if b.id === id) yield b)
    q.firstOption.map{ bm => deleteCache(bm) }
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

  def getSourcesByUser()(implicit session: RSession): Map[Id[User], Seq[BookmarkSource]] =
    StaticQuery.queryNA[(Long, String)]("""select distinct user_Id, source from bookmark where state != 'inactive'""").list.map {
      case (id, source) => (Id[User](id), BookmarkSource.get(source))
    }.groupBy(_._1).mapValues(_.map(_._2))

  def oldestBookmark(userId: Id[User], excludeState: Option[State[Bookmark]] = Some(BookmarkStates.INACTIVE))(implicit session: RSession): Option[Bookmark] = {
    val bookmarks = for { bookmark <- table if bookmark.userId === userId && bookmark.state =!= excludeState.orNull } yield bookmark
    val min = bookmarks.map(_.createdAt).min
    val oldest = for { bookmark <- bookmarks if bookmark.createdAt <= min } yield bookmark
    oldest.sortBy(_.createdAt asc).firstOption
  }
}
