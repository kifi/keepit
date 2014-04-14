package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.slick.jdbc.{PositionedResult, GetResult, StaticQuery}
import com.keepit.common.logging.Logging


@ImplementedBy(classOf[KeepRepoImpl])
trait KeepRepo extends Repo[Keep] with ExternalIdColumnFunction[Keep] with SeqNumberFunction[Keep] {
  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Keep]])(implicit session: RSession): Seq[Keep]
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep]
  def getByExtIdAndUser(extId: ExternalId[Keep], userId: Id[User])(implicit session: RSession): Option[Keep]
  def getPrimaryByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep]
  def getDuplicatesByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Keep]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Keep]
  def getByUser(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def getCountByUser(userId: Id[User], includePrivate: Boolean = true)(implicit session: RSession): Int
  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int)
  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int
  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getBookmarksChanged(num: SequenceNumber[Keep], fetchSize: Int)(implicit session: RSession): Seq[Keep]
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Keep]
  def delete(id: Id[Keep])(implicit session: RWSession): Unit
  def save(model: Keep)(implicit session: RWSession): Keep
  def detectDuplicates()(implicit session: RSession): Seq[(Id[User], Id[NormalizedURI])]
  def latestBookmark(uriId: Id[NormalizedURI])(implicit session: RSession): Option[Keep]
  def getByTitle(title: String)(implicit session: RSession): Seq[Keep]
  def exists(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Boolean
  def getSourcesByUser()(implicit session: RSession) : Map[Id[User], Seq[KeepSource]]
  def oldestBookmark(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
}

@Singleton
class KeepRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val countCache: KeepCountCache,
  bookmarkUriUserCache: KeepUriUserCache,
  latestBookmarkUriCache: LatestKeepUriCache
) extends DbRepo[Keep] with KeepRepo with ExternalIdColumnDbFunction[Keep] with SeqNumberDbFunction[Keep] with Logging {

  import db.Driver.simple._

  private val sequence = db.getSequence[Keep]("bookmark_sequence")

  type RepoImpl = KeepTable
  class KeepTable(tag: Tag) extends RepoTable[Keep](db, tag, "bookmark") with ExternalIdColumn[Keep] with NamedColumns with SeqNumberColumn[Keep]{
    def title = column[String]("title", O.Nullable)//indexd
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)//indexd
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def isPrimary = column[Boolean]("is_primary", O.Nullable) // trueOrNull
    def url =   column[String]("url", O.NotNull)//indexd
    def bookmarkPath = column[String]("bookmark_path", O.Nullable)
    def userId = column[Id[User]]("user_id", O.Nullable)//indexd
    def isPrivate = column[Boolean]("is_private", O.NotNull)//indexd
    def source = column[KeepSource]("source", O.NotNull)
    def kifiInstallation = column[ExternalId[KifiInstallation]]("kifi_installation", O.Nullable)
    def * = (id.?, createdAt, updatedAt, externalId, title.?, uriId, isPrimary.?, urlId, url, bookmarkPath.?, isPrivate,
      userId, state, source, kifiInstallation.?, seq) <> ((Keep.applyWithPrimary _).tupled, Keep.unapplyWithPrimary _)
  }

  def table(tag: Tag) = new KeepTable(tag)
  initTable()

  implicit val getBookmarkSourceResult = getResultFromMapper[KeepSource]
  implicit val setBookmarkSourceParameter = setParameterFromMapper[KeepSource]

  private implicit val getBookmarkResult : GetResult[com.keepit.model.Keep] = GetResult { r => // bonus points for anyone who can do this generically in Slick 2.0
    Keep(id = r.<<[Option[Id[Keep]]], createdAt = r.<<[DateTime], updatedAt = r.<<[DateTime], externalId = r.<<[ExternalId[Keep]], title = r.<<[Option[String]], uriId = r.<<[Id[NormalizedURI]], isPrimary = (r.<<[Option[Boolean]]).exists(b => b), urlId = r.<<[Id[URL]], url = r.<<[String], bookmarkPath = r.<<[Option[String]], isPrivate = r.<<[Boolean], userId = r.<<[Id[User]], state = r.<<[State[Keep]], source = r.<<[KeepSource], kifiInstallation = r.<<[Option[ExternalId[KifiInstallation]]], seq = r.<<[SequenceNumber[Keep]])
  }
  private val bookmarkColumnOrder: String = _taggedTable.columnStrings("bm")


  override def save(model: Keep)(implicit session: RWSession) = {
    assert(model.isPrimary && model.state != KeepStates.DUPLICATE || !model.isPrimary && model.state != KeepStates.ACTIVE,
      s"trying to save a keep in a inconsistent state: primary=${model.isPrimary} state=${model.state}")

    val newModel = model.copy(seq = sequence.incrementAndGet())
    println(s"\n\t newModel = $newModel")

    super.save(newModel.clean())
  }

  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Keep]])(implicit session: RSession): Seq[Keep] =  {
    val q = for {
      t <- rows if (t.isPrivate === false || includePrivate == true) && !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  override def deleteCache(bookmark: Keep)(implicit session: RSession): Unit = {
    bookmarkUriUserCache.remove(KeepUriUserKey(bookmark.uriId, bookmark.userId))
    countCache.remove(KeepCountKey(Some(bookmark.userId)))
    latestBookmarkUriCache.remove(LatestKeepUriKey(bookmark.uriId))
  }

  override def invalidateCache(bookmark: Keep)(implicit session: RSession): Unit = {
    if (bookmark.state == KeepStates.INACTIVE) {
      deleteCache(bookmark)
    } else {
      bookmarkUriUserCache.set(KeepUriUserKey(bookmark.uriId, bookmark.userId), bookmark)
      countCache.remove(KeepCountKey(Some(bookmark.userId)))
      latestBookmarkUriCache.set(LatestKeepUriKey(bookmark.uriId), bookmark)
    }
  }

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] =
    bookmarkUriUserCache.getOrElseOpt(KeepUriUserKey(uriId, userId)) {
      val bookmarks = (for(b <- rows if b.uriId === uriId && b.userId === userId && b.state === KeepStates.ACTIVE) yield b).list
      assert(bookmarks.length <= 1, s"${bookmarks.length} bookmarks found for (uri, user) pair ${(uriId, userId)}")
      bookmarks.headOption
    }

  def getByExtIdAndUser(extId: ExternalId[Keep], userId: Id[User])(implicit session: RSession): Option[Keep] = {
    (for(b <- rows if b.externalId === extId && b.userId === userId) yield b).firstOption
  }

  def getPrimaryByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] ={
    (for(b <- rows if b.uriId === uriId && b.userId === userId && b.isPrimary === true) yield b).firstOption
  }

  def getDuplicatesByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Seq[Keep] ={
    (for(b <- rows if b.uriId === uriId && b.userId === userId && b.state === KeepStates.DUPLICATE) yield b).list
  }

  def getByTitle(title: String)(implicit session: RSession): Seq[Keep] =
    (for(b <- rows if b.title === title) yield b).list

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] =
    (for(b <- rows if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).list

  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Keep] =
    (for(b <- rows if b.uriId === uriId && b.state === KeepStates.ACTIVE && b.title.isNull) yield b).list

  def getByUser(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] =
    (for(b <- rows if b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt).list

  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep] = {
    import StaticQuery.interpolation
    import scala.collection.JavaConversions._

    // Performance sensitive call.
    // Separate queries for each case because the db will cache the query plans when we only use parametrized queries instead of raw strings.
    val interpolated = (beforeId map get, afterId map get) match {
      case (None, None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}' order by bm.created_at desc, bm.id desc limit $count;"""
      case (None, Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
               and (bm.created_at > ${after.createdAt} or (bm.created_at = ${after.createdAt} and bm.id > ${after.id.get}))
               order by bm.created_at desc, bm.id desc limit $count;"""
      case (Some(before), None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
               and (bm.created_at < ${before.createdAt} or (bm.created_at = ${before.createdAt} and bm.id < ${before.id.get}))
               order by bm.created_at desc, bm.id desc limit $count;"""
      case (Some(before), Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
               and (bm.created_at < ${before.createdAt} or (bm.created_at = ${before.createdAt} and bm.id < ${before.id.get}))
               and (bm.created_at > ${after.createdAt} or (bm.created_at = ${after.createdAt} and bm.id > ${after.id.get}))
               order by bm.created_at desc, bm.id desc limit $count;"""
    }
    interpolated.as[Keep].list
  }

  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep] = {
    import StaticQuery.interpolation


    // Performance sensitive call.
    // Separate queries for each case because the db will cache the query plans when we only use parametrized queries.
    val interpolated = (beforeId map get, afterId map get) match {
      case (None, None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' order by bm.created_at desc, bm.id desc limit $count;"""
      case (None, Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' and (bm.created_at > ${after.createdAt} or (bm.created_at = ${after.createdAt} and bm.id > ${after.id.get}))
                order by bm.created_at desc, bm.id desc limit $count;"""
      case (Some(before), None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' and (bm.created_at < ${before.createdAt} or (bm.created_at = ${before.createdAt} and bm.id < ${before.id.get}))
                order by bm.created_at desc, bm.id desc limit $count"""
      case (Some(before), Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' and (bm.created_at < ${before.createdAt} or (bm.created_at = ${before.createdAt} and bm.id < ${before.id.get}))
                and (bm.created_at > ${after.createdAt} or (bm.created_at = ${after.createdAt} and bm.id > ${after.id.get}))
                order by bm.created_at desc, bm.id desc limit $count;"""
    }
    interpolated.as[Keep].list
  }

  def getCountByUser(userId: Id[User], includePrivate: Boolean)(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    if (includePrivate) {
      countCache.getOrElse(KeepCountKey(Some(userId))) {
        val sql = sql"select count(*) from bookmark where user_id=${userId} and state = '#${KeepStates.ACTIVE}'"
        sql.as[Int].first
      }
    } else {
      val sql = sql"select count(*) from bookmark where user_id=${userId} and state = '#${KeepStates.ACTIVE}' and is_private = false"
      sql.as[Int].first
    }
  }

  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    import StaticQuery.interpolation
    val privateCount = sql"select count(*) from bookmark where user_id=${userId} and state = '#${KeepStates.ACTIVE}' and is_private = true".as[Int].first()
    val publicCount = sql"select count(*) from bookmark where user_id=${userId} and state = '#${KeepStates.ACTIVE}' and is_private = false".as[Int].first()
    (privateCount, publicCount)
  }

  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int = {
    import StaticQuery.interpolation

    val sql = sql"select count(*) as c from bookmark where updated_at between ${from} and ${to} and state='#${KeepStates.ACTIVE}';"
    sql.as[Int].first
  }

  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int = {
    import StaticQuery.interpolation

    val sql = sql"select count(*) as c from bookmark b where b.state = '#${KeepStates.ACTIVE}' and b.source=${source} and updated_at between ${from} and ${to};"
    sql.as[Int].first
  }

  def getBookmarksChanged(num: SequenceNumber[Keep], limit: Int)(implicit session: RSession): Seq[Keep] = super.getBySequenceNumber(num, limit)

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Keep] =
    (for(b <- rows if b.urlId === urlId) yield b).list

  def delete(id: Id[Keep])(implicit sesion: RWSession): Unit = {
    val q = (for(b <- rows if b.id === id) yield b)
    q.firstOption.map{ bm => deleteCache(bm) }
    q.delete
  }

  def detectDuplicates()(implicit session: RSession): Seq[(Id[User], Id[NormalizedURI])] = {
    val q = for {
      r <- rows
      s <- rows if (r.userId === s.userId && r.uriId === s.uriId && r.id < s.id)
    } yield (r.userId, r.uriId)
    q.list.distinct
  }

  def latestBookmark(uriId: Id[NormalizedURI])(implicit session: RSession): Option[Keep] = {
    latestBookmarkUriCache.getOrElseOpt(LatestKeepUriKey(uriId)) {
      val bookmarks = for { bookmark <- rows if bookmark.uriId === uriId } yield bookmark
      val max = bookmarks.map(_.updatedAt).max
      val latest = for { bookmark <- bookmarks if bookmark.updatedAt >= max } yield bookmark
      latest.sortBy(_.updatedAt desc).firstOption
    }
  }

  def exists(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Boolean = {
    (for(b <- rows if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).firstOption.isDefined
  }

  def getSourcesByUser()(implicit session: RSession): Map[Id[User], Seq[KeepSource]] =
    StaticQuery.queryNA[(Long, String)]("""select distinct user_id, source from bookmark where state != '#${BookmarkStates.INACTIVE}'""").list.map {
      case (id, source) => (Id[User](id), KeepSource.get(source))
    }.groupBy(_._1).mapValues(_.map(_._2))

  def oldestBookmark(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] = {
    val bookmarks = for { bookmark <- rows if bookmark.userId === userId && bookmark.state =!= excludeState.orNull } yield bookmark
    val min = bookmarks.map(_.createdAt).min
    val oldest = for { bookmark <- bookmarks if bookmark.createdAt <= min } yield bookmark
    oldest.sortBy(_.createdAt asc).firstOption
  }
}
