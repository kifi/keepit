package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.slick.jdbc.{ PositionedResult, GetResult, StaticQuery }
import com.keepit.common.logging.Logging
import com.keepit.commanders.WhoKeptMyKeeps
import com.keepit.common.core._

@ImplementedBy(classOf[KeepRepoImpl])
trait KeepRepo extends Repo[Keep] with ExternalIdColumnFunction[Keep] with SeqNumberFunction[Keep] {
  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Keep]])(implicit session: RSession): Seq[Keep]
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] //todo: replace option with seq
  def getAllByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep]
  def getByExtIdAndUser(extId: ExternalId[Keep], userId: Id[User])(implicit session: RSession): Option[Keep]
  def getPrimaryByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library])(implicit session: RSession): Option[Keep]
  def getPrimaryInDisjointByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def countPublicActiveByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Int
  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Keep]
  def getByUser(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def bulkGetByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Map[Id[NormalizedURI], Keep]
  def getCountByUser(userId: Id[User])(implicit session: RSession): Int
  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int)
  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int
  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getAllCountsByTimeAndSource(from: DateTime, to: DateTime)(implicit session: RSession): Seq[(KeepSource, Int)]
  def getPrivateCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getBookmarksChanged(num: SequenceNumber[Keep], fetchSize: Int)(implicit session: RSession): Seq[Keep]
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Keep]
  def delete(id: Id[Keep])(implicit session: RWSession): Unit
  def save(model: Keep)(implicit session: RWSession): Keep
  def detectDuplicates()(implicit session: RSession): Seq[(Id[User], Id[NormalizedURI])]
  def latestKeep(uriId: Id[NormalizedURI], url: String)(implicit session: RSession): Option[Keep]
  def getByTitle(title: String)(implicit session: RSession): Seq[Keep]
  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
  def getSourcesByUser()(implicit session: RSession): Map[Id[User], Seq[KeepSource]]
  def oldestKeep(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def whoKeptMyKeeps(userId: Id[User], since: DateTime, maxKeepers: Int)(implicit session: RSession): Seq[WhoKeptMyKeeps]
  def getLatestKeepsURIByUser(userId: Id[User], limit: Int, includePrivate: Boolean = false)(implicit session: RSession): Seq[Id[NormalizedURI]]
  def getKeepExports(userId: Id[User])(implicit session: RSession): Seq[KeepExport]
  def getByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getCountByLibrary(libraryId: Id[Library], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Int
  def getByExtIdandLibraryId(extId: ExternalId[Keep], libraryId: Id[Library], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def getKeepsFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[Keep]
  def librariesWithMostKeepsSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)]
}

@Singleton
class KeepRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val countCache: KeepCountCache,
    keepUriUserCache: KeepUriUserCache,
    libraryMetadataCache: LibraryMetadataCache,
    countByLibraryCache: CountByLibraryCache,
    latestKeepUriCache: LatestKeepUriCache,
    latestKeepUrlCache: LatestKeepUrlCache) extends DbRepo[Keep] with KeepRepo with ExternalIdColumnDbFunction[Keep] with SeqNumberDbFunction[Keep] with Logging {

  import db.Driver.simple._

  private val sequence = db.getSequence[Keep]("bookmark_sequence")

  type RepoImpl = KeepTable
  class KeepTable(tag: Tag) extends RepoTable[Keep](db, tag, "bookmark") with ExternalIdColumn[Keep] with NamedColumns with SeqNumberColumn[Keep] {
    def title = column[String]("title", O.Nullable) //indexd
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull) //indexd
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def isPrimary = column[Boolean]("is_primary", O.Nullable) // trueOrNull
    def inDisjointLib = column[Boolean]("in_disjoint_lib", O.Nullable) // trueOrNull
    def url = column[String]("url", O.NotNull) //indexd
    def bookmarkPath = column[String]("bookmark_path", O.Nullable)
    def userId = column[Id[User]]("user_id", O.Nullable) //indexd
    def isPrivate = column[Boolean]("is_private", O.NotNull) //indexd
    def source = column[KeepSource]("source", O.NotNull)
    def kifiInstallation = column[ExternalId[KifiInstallation]]("kifi_installation", O.Nullable)
    def libraryId = column[Option[Id[Library]]]("library_id", O.Nullable)
    def visibility = column[LibraryVisibility]("visibility", O.Nullable)

    def * = (id.?, createdAt, updatedAt, externalId, title.?, uriId, isPrimary.?, inDisjointLib.?, urlId, url, bookmarkPath.?, isPrivate,
      userId, state, source, kifiInstallation.?, seq, libraryId, visibility.?) <> ((Keep.applyFromDbRow _).tupled, Keep.unapplyToDbRow _)
  }

  def table(tag: Tag) = new KeepTable(tag)
  initTable()

  implicit val getBookmarkSourceResult = getResultFromMapper[KeepSource]
  implicit val setBookmarkSourceParameter = setParameterFromMapper[KeepSource]

  private implicit val getBookmarkResult: GetResult[com.keepit.model.Keep] = GetResult { r: PositionedResult => // bonus points for anyone who can do this generically in Slick 2.0
    var privateFlag: Boolean = false

    Keep.applyFromDbRow(
      id = r.<<[Option[Id[Keep]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      externalId = r.<<[ExternalId[Keep]],
      title = r.<<[Option[String]],
      uriId = r.<<[Id[NormalizedURI]],
      isPrimary = r.<<[Option[Boolean]],
      inDisjointLib = r.<<[Option[Boolean]],
      urlId = r.<<[Id[URL]],
      url = r.<<[String],
      bookmarkPath = r.<<[Option[String]],
      isPrivate = { privateFlag = r.<<[Boolean]; privateFlag }, // todo(andrew): wowza, clean up when done with libraries
      userId = r.<<[Id[User]],
      state = r.<<[State[Keep]],
      source = r.<<[KeepSource],
      kifiInstallation = r.<<[Option[ExternalId[KifiInstallation]]],
      seq = r.<<[SequenceNumber[Keep]],
      libraryId = r.<<[Option[Id[Library]]],
      visibility = Some(r.<<[Option[LibraryVisibility]].getOrElse(Keep.isPrivateToVisibility(privateFlag)))
    )
  }
  private val bookmarkColumnOrder: String = _taggedTable.columnStrings("bm")

  override def save(model: Keep)(implicit session: RWSession) = {
    assert(model.isPrimary && model.state != KeepStates.DUPLICATE || !model.isPrimary && model.state != KeepStates.ACTIVE,
      s"trying to save a keep in an inconsistent state: primary=${model.isPrimary} state=${model.state}")

    val newModel = model.copy(seq = sequence.incrementAndGet())
    super.save(newModel.clean())
  }

  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Keep]])(implicit session: RSession): Seq[Keep] = {
    val q = for {
      t <- rows if (t.isPrivate === false || includePrivate == true) && !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  override def deleteCache(keep: Keep)(implicit session: RSession): Unit = {
    keep.libraryId foreach { id =>
      countByLibraryCache.remove(CountByLibraryKey(id))
      libraryMetadataCache.remove(LibraryMetadataKey(id))
    }
    keepUriUserCache.remove(KeepUriUserKey(keep.uriId, keep.userId))
    countCache.remove(KeepCountKey(keep.userId))
    latestKeepUriCache.remove(LatestKeepUriKey(keep.uriId))
    latestKeepUrlCache.remove(LatestKeepUrlKey(keep.url))
  }

  override def invalidateCache(keep: Keep)(implicit session: RSession): Unit = {
    keep.libraryId foreach { id =>
      countByLibraryCache.remove(CountByLibraryKey(id))
      libraryMetadataCache.remove(LibraryMetadataKey(id))
    }
    if (keep.state == KeepStates.INACTIVE) {
      deleteCache(keep)
    } else {
      keepUriUserCache.set(KeepUriUserKey(keep.uriId, keep.userId), keep)
      countCache.remove(KeepCountKey(keep.userId))
      val latestKeepUriKey = LatestKeepUriKey(keep.uriId)
      if (!latestKeepUriCache.get(latestKeepUriKey).exists(_.createdAt.isAfter(keep.createdAt))) { latestKeepUriCache.set(latestKeepUriKey, keep) }
      val latestKeepUrlKey = LatestKeepUrlKey(keep.url)
      if (!latestKeepUrlCache.get(latestKeepUrlKey).exists(_.createdAt.isAfter(keep.createdAt))) { latestKeepUrlCache.set(latestKeepUrlKey, keep) }
    }
  }

  // preserved for backward compatibility
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] =
    keepUriUserCache.getOrElseOpt(KeepUriUserKey(uriId, userId)) {
      val keeps = (for (b <- rows if b.uriId === uriId && b.userId === userId && b.isPrimary === true && b.state === KeepStates.ACTIVE) yield b).list
      if (keeps.length > 1) log.warn(s"[getByUriAndUser] ${keeps.length} keeps found for (uri, user) pair ${(uriId, userId)}")
      keeps.find(_.inDisjointLib).orElse(keeps.headOption) // order: disjoint, custom
    }

  def getAllByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.userId === userId && b.uriId.inSet(uriIds) && b.state === KeepStates.ACTIVE) yield b).list
  }

  def getByExtIdAndUser(extId: ExternalId[Keep], userId: Id[User])(implicit session: RSession): Option[Keep] = {
    (for (b <- rows if b.externalId === extId && b.userId === userId) yield b).firstOption
  }

  def getPrimaryByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library])(implicit session: RSession): Option[Keep] = {
    (for (b <- rows if b.uriId === uriId && b.libraryId === libId && b.isPrimary === true) yield b).firstOption
  }

  def getPrimaryInDisjointByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] = {
    (for (b <- rows if b.uriId === uriId && b.userId === userId && b.inDisjointLib && b.isPrimary === true) yield b).firstOption
  }

  def getByTitle(title: String)(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.title === title) yield b).list

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).list

  def countPublicActiveByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    import scala.collection.JavaConversions._
    sql"select count(*) from bookmark where uri_id = $uriId and is_private = false and state = 'active'".as[Int].first
  }

  def getByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.uriId === uriId && b.state === KeepStates.ACTIVE && b.title.isNull) yield b).list

  def getByUser(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt).list

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

  def whoKeptMyKeeps(userId: Id[User], since: DateTime, maxKeepers: Int)(implicit session: RSession): Seq[WhoKeptMyKeeps] = {
    import StaticQuery.interpolation
    val interpolated = sql"""
          SELECT b.c user_count, b.t last_keep_time, b.uri_id, b.users FROM (
            SELECT count(ab.id) c, max(ab.created_at) t, ab.uri_id, group_concat(ab.user_id ORDER BY ab.created_at DESC) users FROM (
              SELECT uri_id FROM bookmark
              WHERE user_id = ${userId}
            ) ub, bookmark ab
            WHERE ub.uri_id = ab.uri_id AND ab.is_private = FALSE AND ab.user_id != ${userId}
            GROUP BY ab.uri_id
          ) b
          WHERE b.t > ${since} AND b.c BETWEEN 1 AND ${maxKeepers}
          ORDER BY b.t DESC;"""
    interpolated.as[(Int, DateTime, Id[NormalizedURI], String)].list map { row =>
      WhoKeptMyKeeps(row._1, row._2, row._3, row._4.split(',').map(_.toInt).map(Id[User](_)))
    }
  }

  def bulkGetByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Map[Id[NormalizedURI], Keep] = {
    val res = keepUriUserCache.bulkGetOrElse(uriIds.map(KeepUriUserKey(_, userId))) { keys =>
      val missing = keys.map(_.uriId)
      val keeps = (for (r <- rows if r.userId === userId && r.uriId.inSet(missing) && r.state === KeepStates.ACTIVE) yield r).list()
      keeps.map { k => KeepUriUserKey(k.uriId, userId) -> k }.toMap
    }
    res.map { case (key, keep) => key.uriId -> keep }
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

  def getCountByUser(userId: Id[User])(implicit session: RSession): Int = {
    import StaticQuery.interpolation

    val sql = sql"select count(*) from bookmark where user_id=${userId} and state = '#${KeepStates.ACTIVE}'"
    sql.as[Int].first
  }

  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    import StaticQuery.interpolation

    val sql = sql"select sum(is_private), sum(1 - is_private) from bookmark where user_id=${userId} and state = '#${KeepStates.ACTIVE}'"
    sql.as[(Int, Int)].first()
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

  def getAllCountsByTimeAndSource(from: DateTime, to: DateTime)(implicit session: RSession): Seq[(KeepSource, Int)] = {
    import StaticQuery.interpolation

    val sql = sql"""select source, count(*) from bookmark b
      where b.state = '#${KeepStates.ACTIVE}' and created_at between ${from} and ${to}
      group by b.source;"""
    sql.as[(KeepSource, Int)].list
  }

  def getPrivateCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int = {
    import StaticQuery.interpolation

    val sql = sql"select count(*) as c from bookmark b where b.state = '#${KeepStates.ACTIVE}' and b.is_private = 1 and b.source=${source} and updated_at between ${from} and ${to};"
    sql.as[Int].first
  }

  def getBookmarksChanged(num: SequenceNumber[Keep], limit: Int)(implicit session: RSession): Seq[Keep] = super.getBySequenceNumber(num, limit)

  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.urlId === urlId) yield b).list

  def delete(id: Id[Keep])(implicit sesion: RWSession): Unit = {
    val q = (for (b <- rows if b.id === id) yield b)
    q.firstOption.map { bm => deleteCache(bm) }
    q.delete
  }

  def detectDuplicates()(implicit session: RSession): Seq[(Id[User], Id[NormalizedURI])] = {
    val q = for {
      r <- rows
      s <- rows if (r.userId === s.userId && r.uriId === s.uriId && r.id < s.id)
    } yield (r.userId, r.uriId)
    q.list.distinct
  }

  def latestKeep(uriId: Id[NormalizedURI], url: String)(implicit session: RSession): Option[Keep] = {
    latestKeepUriCache.getOrElseOpt(LatestKeepUriKey(uriId)) {
      val bookmarks = for { bookmark <- rows if bookmark.uriId === uriId && bookmark.url === url } yield bookmark
      val max = bookmarks.map(_.createdAt).max
      val latest = for { bookmark <- bookmarks if bookmark.createdAt >= max } yield bookmark
      latest.sortBy(_.createdAt desc).firstOption
    }
  }

  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (b <- rows if b.uriId === uriId && b.state === KeepStates.ACTIVE) yield b).firstOption.isDefined
  }

  def getSourcesByUser()(implicit session: RSession): Map[Id[User], Seq[KeepSource]] =
    StaticQuery.queryNA[(Long, String)]("""select distinct user_id, source from bookmark where state != '#${BookmarkStates.INACTIVE}'""").list.map {
      case (id, source) => (Id[User](id), KeepSource.get(source))
    }.groupBy(_._1).mapValues(_.map(_._2))

  def oldestKeep(userId: Id[User], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] = {
    val bookmarks = for { bookmark <- rows if bookmark.userId === userId && bookmark.state =!= excludeState.orNull } yield bookmark
    val min = bookmarks.map(_.createdAt).min
    val oldest = for { bookmark <- bookmarks if bookmark.createdAt <= min } yield bookmark
    oldest.sortBy(_.createdAt asc).firstOption
  }

  def getLatestKeepsURIByUser(userId: Id[User], limit: Int, includePrivate: Boolean = false)(implicit session: RSession): Seq[Id[NormalizedURI]] = {
    import StaticQuery.interpolation

    val sql = if (includePrivate) sql"select uri_Id from bookmark where state = '#${KeepStates.ACTIVE}' and user_id=${userId} order by created_at DESC limit ${limit}"
    else sql"select uri_Id from bookmark where state = '#${KeepStates.ACTIVE}' and user_id=${userId} and is_private = false order by created_at DESC limit ${limit}"

    sql.as[Id[NormalizedURI]].list
  }

  def getKeepExports(userId: Id[User])(implicit session: RSession): Seq[KeepExport] = {
    import StaticQuery.interpolation
    val sqlQuery = sql"""select k.created_at, k.title, k.url, group_concat(c.name)
      from bookmark k left join keep_to_collection kc
      on kc.bookmark_id = k.id left join collection c on c.id = kc.collection_id where k.user_id = ${userId} and k.state = '#${KeepStates.ACTIVE}'
      group by url order by k.id desc"""
    sqlQuery.as[(DateTime, Option[String], String, Option[String])].list.map { case (created_at, title, url, tags) => KeepExport(created_at, title, url, tags) }
  }

  // Make compiled in Slick 2.1

  def getByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.libraryId === libraryId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt desc).drop(offset).take(limit).list
  }

  private val getCountByLibraryCompiled = Compiled { (libraryId: Column[Id[Library]]) =>
    (for (b <- rows if b.libraryId === libraryId) yield b).length
  }
  private val getCountByLibraryWithExcludeCompiled = Compiled { (libraryId: Column[Id[Library]], excludeState: Column[State[Keep]]) =>
    (for (b <- rows if b.libraryId === libraryId && b.state =!= excludeState) yield b).length
  }
  def getCountByLibrary(libraryId: Id[Library], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Int = {
    countByLibraryCache.getOrElse(CountByLibraryKey(libraryId)) {
      excludeState match {
        case None => getCountByLibraryCompiled(libraryId).run
        case Some(exclude) => getCountByLibraryWithExcludeCompiled(libraryId, exclude).run
      }
    }
  }

  private val getByExtIdandLibraryIdCompiled = Compiled { (extId: Column[ExternalId[Keep]], libraryId: Column[Id[Library]]) =>
    (for (b <- rows if b.externalId === extId && b.libraryId === libraryId) yield b)
  }
  private val getByExtIdandLibraryIdWithExcludeCompiled = Compiled { (extId: Column[ExternalId[Keep]], libraryId: Column[Id[Library]], excludeState: Column[State[Keep]]) =>
    (for (b <- rows if b.externalId === extId && b.libraryId === libraryId && b.state =!= excludeState) yield b)
  }
  def getByExtIdandLibraryId(extId: ExternalId[Keep], libraryId: Id[Library], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] = {
    excludeState match {
      case None => getByExtIdandLibraryIdCompiled(extId, libraryId).firstOption
      case Some(exclude) => getByExtIdandLibraryIdWithExcludeCompiled(extId, libraryId, exclude).firstOption
    }
  }

  def getKeepsFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.libraryId === library && b.state === KeepStates.ACTIVE && b.createdAt > since) yield b).sortBy(_.createdAt asc).take(max).list
  }

  def librariesWithMostKeepsSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)] = {
    import StaticQuery.interpolation
    sql"""select b.library_id, count(*) as cnt from bookmark b, library l where l.id = b.library_id and l.state='active' and l.visibility='published' and b.created_at > $since group by b.library_id order by count(*) desc limit $count""".as[(Id[Library], Int)].list
  }
}
