package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.slick.jdbc.{ PositionedResult, GetResult, StaticQuery }
import com.keepit.common.logging.Logging
import com.keepit.commanders.{ LibraryMetadataKey, LibraryMetadataCache, WhoKeptMyKeeps }
import com.keepit.common.core._

@ImplementedBy(classOf[KeepRepoImpl])
trait KeepRepo extends Repo[Keep] with ExternalIdColumnFunction[Keep] with SeqNumberFunction[Keep] {
  def getByIds(ids: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Keep]
  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Keep]])(implicit session: RSession): Seq[Keep]
  def getByExtId(extId: ExternalId[Keep], excludeStates: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def getByExtIds(extIds: Set[ExternalId[Keep]])(implicit session: RSession): Map[ExternalId[Keep], Option[Keep]]
  def getByExtIdAndUser(extId: ExternalId[Keep], userId: Id[User])(implicit session: RSession): Option[Keep] // TODO(ryan)[2015-08-03]: deprecate this method ASAP
  def getByExtIdandLibraryId(extId: ExternalId[Keep], libraryId: Id[Library], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] // TODO(ryan)[2015-08-03]: deprecate ASAP

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] //todo: replace option with seq
  def getByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def countPublicActiveByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Int
  def getByUser(userId: Id[User], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def getNonPrivate(ownerId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[Keep]
  def getPrivate(ownerId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[Keep]
  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def bulkGetByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Map[Id[NormalizedURI], Keep]
  def getCountByUser(userId: Id[User])(implicit session: RSession): Int
  def getCountManualByUserInLastDays(userId: Id[User], days: Int)(implicit session: RSession): Int
  def getCountByUsers(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int]
  def getCountByUsersAndSource(userIds: Set[Id[User]], sources: Set[KeepSource])(implicit session: RSession): Map[Id[User], Int]
  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int)
  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int
  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getAllCountsByTimeAndSource(from: DateTime, to: DateTime)(implicit session: RSession): Seq[(KeepSource, Int)]
  def getPrivateCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getBookmarksChanged(num: SequenceNumber[Keep], fetchSize: Int)(implicit session: RSession): Seq[Keep]
  def getByUrlId(urlId: Id[URL])(implicit session: RSession): Seq[Keep]
  def delete(id: Id[Keep])(implicit session: RWSession): Unit
  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
  def getSourcesByUser()(implicit session: RSession): Map[Id[User], Seq[KeepSource]]
  def whoKeptMyKeeps(userId: Id[User], since: DateTime, maxKeepers: Int)(implicit session: RSession): Seq[WhoKeptMyKeeps]
  def getLatestKeepsURIByUser(userId: Id[User], limit: Int, includePrivate: Boolean = false)(implicit session: RSession): Seq[Id[NormalizedURI]]
  def getKeepExports(userId: Id[User])(implicit session: RSession): Seq[KeepExport]
  def latestManualKeepTime(userId: Id[User])(implicit session: RSession): Option[DateTime]
  def getKeepsByTimeWindow(uriId: Id[NormalizedURI], url: String, keptAfter: DateTime, keptBefore: DateTime)(implicit session: RSession): Set[Keep]
  def getKeepSourcesByUser(userId: Id[User])(implicit session: RSession): Seq[KeepSource]

  // TODO(ryan): All of these methods are going to have to migrate to KeepToLibraryRepo
  // These ones already exist there:
  def getByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getCountByLibrary(libraryId: Id[Library])(implicit session: RSession): Int
  def getCountsByLibrary(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Int]
  def getByUserIdAndLibraryId(userId: Id[User], libraryId: Id[Library], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[Keep]
  def recentKeepNotes(libId: Id[Library], limit: Int)(implicit session: RSession): Seq[String]
  def getByLibraryIdsAndUriIds(libraryIds: Set[Id[Library]], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep]
  def getPrimaryByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library])(implicit session: RSession): Option[Keep]
  def getByLibraryIdAndExcludingVisibility(libId: Id[Library], excludeVisibility: Option[LibraryVisibility], limit: Int)(implicit session: RSession): Seq[Keep]
  def getByLibraryWithInconsistentOrgId(libraryId: Id[Library], expectedOrgId: Option[Id[Organization]], limit: Limit)(implicit session: RSession): Set[Id[Keep]]
  def getKeepsFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[Keep]
  def getRecentKeepsFromFollowedLibraries(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]])(implicit session: RSession): Seq[Keep]
  def librariesWithMostKeepsSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)]
  def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]]
  def latestKeptAtByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[DateTime]]
  def deactivate(model: Keep)(implicit session: RWSession): Unit
}

@Singleton
class KeepRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val countCache: KeepCountCache,
    keepByIdCache: KeepByIdCache,
    keepUriUserCache: KeepUriUserCache,
    libraryMetadataCache: LibraryMetadataCache,
    countByLibraryCache: CountByLibraryCache) extends DbRepo[Keep] with KeepRepo with ExternalIdColumnDbFunction[Keep] with SeqNumberDbFunction[Keep] with Logging {

  import db.Driver.simple._

  type RepoImpl = KeepTable
  class KeepTable(tag: Tag) extends RepoTable[Keep](db, tag, "bookmark") with ExternalIdColumn[Keep] with SeqNumberColumn[Keep] with NamedColumns {
    def title = column[Option[String]]("title", O.Nullable) //indexd
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull) //indexd
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def isPrimary = column[Option[Boolean]]("is_primary", O.Nullable) // trueOrNull
    def url = column[String]("url", O.NotNull) //indexd
    def userId = column[Id[User]]("user_id", O.Nullable) //indexd
    def isPrivate = column[Boolean]("is_private", O.NotNull) //indexd
    def source = column[KeepSource]("source", O.NotNull)
    def kifiInstallation = column[Option[ExternalId[KifiInstallation]]]("kifi_installation", O.Nullable)
    def libraryId = column[Option[Id[Library]]]("library_id", O.Nullable)
    def visibility = column[LibraryVisibility]("visibility", O.NotNull)
    def keptAt = column[DateTime]("kept_at", O.NotNull)
    def sourceAttributionId = column[Option[Id[KeepSourceAttribution]]]("source_attribution_id", O.Nullable)
    def note = column[Option[String]]("note", O.Nullable)
    def originalKeeperId = column[Option[Id[User]]]("original_keeper_id", O.Nullable)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)

    def * = ((id.?, createdAt, updatedAt, externalId, title, uriId, isPrimary, urlId, url),
      (isPrivate, userId, state, source, kifiInstallation, seq, libraryId, visibility, keptAt, sourceAttributionId,
        note, originalKeeperId, organizationId)).shaped <> ({ case (first10, rest) => Keep.applyFromDbRowTuples(first10, rest) }, { Keep.unapplyToDbRow _ })
  }

  def table(tag: Tag) = new KeepTable(tag)
  initTable()

  implicit val getBookmarkSourceResult = getResultFromMapper[KeepSource]
  implicit val setBookmarkSourceParameter = setParameterFromMapper[KeepSource]

  private implicit val getBookmarkResult: GetResult[com.keepit.model.Keep] = GetResult { r: PositionedResult => // bonus points for anyone who can do this generically in Slick 2.0
    Keep._applyFromDbRow(
      id = r.<<[Option[Id[Keep]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      externalId = r.<<[ExternalId[Keep]],
      title = r.<<[Option[String]],
      uriId = r.<<[Id[NormalizedURI]],
      isPrimary = r.<<[Option[Boolean]],
      urlId = r.<<[Id[URL]],
      url = r.<<[String],
      isPrivate = r.<<[Boolean],
      userId = r.<<[Id[User]],
      state = r.<<[State[Keep]],
      source = r.<<[KeepSource],
      kifiInstallation = r.<<[Option[ExternalId[KifiInstallation]]],
      seq = r.<<[SequenceNumber[Keep]],
      libraryId = r.<<[Option[Id[Library]]],
      visibility = r.<<[LibraryVisibility],
      keptAt = r.<<[DateTime],
      sourceAttributionId = r.<<[Option[Id[KeepSourceAttribution]]],
      note = r.<<[Option[String]],
      originalKeeperId = r.<<[Option[Id[User]]],
      organizationId = r.<<[Option[Id[Organization]]]
    )
  }
  private val bookmarkColumnOrder: String = _taggedTable.columnStrings("bm")

  // Note: if you decide to use update() instead of save(), please ensure deferredSeqNum is used
  override def save(model: Keep)(implicit session: RWSession) = {
    val newModel = if (model.id.isDefined || KeepSource.imports.contains(model.source)) {
      model.copy(seq = deferredSeqNum()) // Always use deferred for imports or updates
    } else {
      model.copy(seq = sequence.incrementAndGet())
    }
    super.save(newModel.clean())
  }

  def deactivate(model: Keep)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
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
    keepByIdCache.remove(KeepIdKey(keep.id.get))
    keepUriUserCache.remove(KeepUriUserKey(keep.uriId, keep.userId))
    countCache.remove(KeepCountKey(keep.userId))
  }

  override def invalidateCache(keep: Keep)(implicit session: RSession): Unit = {
    keep.libraryId foreach { id =>
      countByLibraryCache.remove(CountByLibraryKey(id))
      libraryMetadataCache.remove(LibraryMetadataKey(id))
    }
    if (keep.state == KeepStates.INACTIVE) {
      deleteCache(keep)
    } else {
      keepByIdCache.set(KeepIdKey(keep.id.get), keep)
      keepUriUserCache.set(KeepUriUserKey(keep.uriId, keep.userId), keep)
      countCache.remove(KeepCountKey(keep.userId))
    }
  }

  override def get(id: Id[Keep])(implicit session: RSession): Keep = {
    keepByIdCache.getOrElse(KeepIdKey(id)) {
      getCompiled(id).firstOption.getOrElse(throw NotFoundException(id))
    }
  }

  def getByIds(ids: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Keep] = {
    keepByIdCache.bulkGetOrElse(ids.map(KeepIdKey)) { missingKeys =>
      val q = { for { row <- rows if row.id.inSet(missingKeys.map(_.id)) } yield row }
      q.list.map { x => KeepIdKey(x.id.get) -> x }.toMap
    }.map { case (key, org) => key.id -> org }
  }

  def getByExtId(extId: ExternalId[Keep], excludeStates: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] = {
    getOpt(extId).filter(k => !excludeStates.contains(k.state))
  }
  def getByExtIds(extIds: Set[ExternalId[Keep]])(implicit session: RSession): Map[ExternalId[Keep], Option[Keep]] = {
    if (extIds.isEmpty) {
      Map.empty[ExternalId[Keep], Option[Keep]] // return immediately, don't search through table
    } else if (extIds.size == 1) {
      val extId = extIds.head
      Map((extId, getOpt(extId))) // defer to precompiled query
    } else {
      val keepMap = (for (b <- rows if b.externalId.inSet(extIds) && b.state === KeepStates.ACTIVE) yield b).list.map { keep =>
        (keep.externalId, keep)
      }.toMap
      extIds.map { extId =>
        extId -> (keepMap.get(extId) orElse None)
      }.toMap
    }
  }

  // preserved for backward compatibility
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] =
    keepUriUserCache.getOrElseOpt(KeepUriUserKey(uriId, userId)) {
      val keeps = (for (b <- rows if b.uriId === uriId && b.userId === userId && b.isPrimary === true && b.state === KeepStates.ACTIVE) yield b).list
      if (keeps.length > 1) log.warn(s"[getByUriAndUser] ${keeps.length} keeps found for (uri, user) pair ${(uriId, userId)}")
      keeps.headOption
    }

  def getByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.userId === userId && b.uriId.inSet(uriIds) && b.state === KeepStates.ACTIVE) yield b).list
  }

  def getByLibraryIdsAndUriIds(libraryIds: Set[Id[Library]], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.uriId.inSet(uriIds) && b.libraryId.inSet(libraryIds) && b.state === KeepStates.ACTIVE) yield b).list
  }

  def getByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[Keep] = {
    (for (row <- rows if row.libraryId.inSet(libraryIds) && row.state === KeepStates.ACTIVE) yield row).list
  }

  def getByUserIdAndLibraryId(userId: Id[User], libraryId: Id[Library], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.libraryId === libraryId && b.userId === userId && !b.state.inSet(excludeSet)) yield b).list
  }

  def getByExtIdAndUser(extId: ExternalId[Keep], userId: Id[User])(implicit session: RSession): Option[Keep] = {
    getByExtId(extId) flatMap { keep =>
      if (keep.userId != userId) {
        log.info("[KTL] unexpected keep owner: $")
        None
      } else {
        Some(keep)
      }
    }
  }

  def getPrimaryByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library])(implicit session: RSession): Option[Keep] = {
    (for (b <- rows if b.uriId === uriId && b.libraryId === libId && b.isPrimary === true) yield b).firstOption
  }

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).list

  def countPublicActiveByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import scala.collection.JavaConversions._
    sql"select count(*) from bookmark where uri_id = $uriId and is_private = false and state = 'active'".as[Int].first
  }

  def getByUser(userId: Id[User], excludeSet: Set[State[Keep]])(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.userId === userId && !b.state.inSet(excludeSet)) yield b).sortBy(_.keptAt).list

  def getNonPrivate(ownerId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import scala.collection.JavaConversions._
    val interpolated = sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${ownerId} and bm.state = '#${KeepStates.ACTIVE}' and bm.visibility != '#${LibraryVisibility.SECRET.value}' order by bm.kept_at desc, bm.id desc limit $offset, $limit;"""
    interpolated.as[Keep].list
  }

  def getPrivate(ownerId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import scala.collection.JavaConversions._
    val interpolated = sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${ownerId} and bm.state = '#${KeepStates.ACTIVE}' and bm.visibility = '#${LibraryVisibility.SECRET.value}' order by bm.kept_at desc, bm.id desc limit $offset, $limit;"""
    interpolated.as[Keep].list
  }

  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import scala.collection.JavaConversions._

    // Performance sensitive call.
    // Separate queries for each case because the db will cache the query plans when we only use parametrized queries instead of raw strings.
    val interpolated = (beforeId map get, afterId map get) match {
      case (None, None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}' order by bm.kept_at desc, bm.id desc limit $count;"""
      case (None, Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
               and (bm.kept_at > ${after.keptAt} or (bm.kept_at = ${after.keptAt} and bm.id > ${after.id.get}))
               order by bm.kept_at desc, bm.id desc limit $count;"""
      case (Some(before), None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
               and (bm.kept_at < ${before.keptAt} or (bm.kept_at = ${before.keptAt} and bm.id < ${before.id.get}))
               order by bm.kept_at desc, bm.id desc limit $count;"""
      case (Some(before), Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
               and (bm.kept_at < ${before.keptAt} or (bm.kept_at = ${before.keptAt} and bm.id < ${before.id.get}))
               and (bm.kept_at > ${after.keptAt} or (bm.kept_at = ${after.keptAt} and bm.id > ${after.id.get}))
               order by bm.kept_at desc, bm.id desc limit $count;"""
    }
    interpolated.as[Keep].list
  }

  def whoKeptMyKeeps(userId: Id[User], since: DateTime, maxKeepers: Int)(implicit session: RSession): Seq[WhoKeptMyKeeps] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val interpolated = sql"""
          SELECT b.c user_count, b.t last_keep_time, b.uri_id, b.users FROM (
            SELECT count(ab.id) c, max(ab.kept_at) t, ab.uri_id, group_concat(ab.user_id ORDER BY ab.kept_at DESC) users FROM (
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
      val keeps = (for (r <- rows if r.userId === userId && r.uriId.inSet(missing) && r.state === KeepStates.ACTIVE) yield r).list
      keeps.map { k => KeepUriUserKey(k.uriId, userId) -> k }.toMap
    }
    res.map { case (key, keep) => key.uriId -> keep }
  }

  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    // Performance sensitive call.
    // Separate queries for each case because the db will cache the query plans when we only use parametrized queries.
    val interpolated = (beforeId map get, afterId map get) match {
      case (None, None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' order by bm.kept_at desc, bm.id desc limit $count;"""
      case (None, Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' and (bm.kept_at > ${after.keptAt} or (bm.kept_at = ${after.keptAt} and bm.id > ${after.id.get}))
                order by bm.kept_at desc, bm.id desc limit $count;"""
      case (Some(before), None) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' and (bm.kept_at < ${before.keptAt} or (bm.kept_at = ${before.keptAt} and bm.id < ${before.id.get}))
                order by bm.kept_at desc, bm.id desc limit $count"""
      case (Some(before), Some(after)) =>
        sql"""select #$bookmarkColumnOrder from bookmark bm left join keep_to_collection kc on (bm.id = kc.bookmark_id)
                where kc.collection_id = ${collectionId} and bm.user_id = ${userId} and bm.state = '#${KeepStates.ACTIVE}'
                and kc.state='#${KeepToCollectionStates.ACTIVE}' and (bm.kept_at < ${before.keptAt} or (bm.kept_at = ${before.keptAt} and bm.id < ${before.id.get}))
                and (bm.kept_at > ${after.keptAt} or (bm.kept_at = ${after.keptAt} and bm.id > ${after.id.get}))
                order by bm.kept_at desc, bm.id desc limit $count;"""
    }
    interpolated.as[Keep].list
  }

  def getCountByUser(userId: Id[User])(implicit session: RSession): Int = {
    rows.filter(k => k.userId === userId && k.state === KeepStates.ACTIVE).length.run
  }

  def getCountManualByUserInLastDays(userId: Id[User], days: Int)(implicit session: RSession): Int = {
    rows.filter(k => k.userId === userId && k.state === KeepStates.ACTIVE && k.keptAt > clock.now().minusDays(days) && k.source.inSet(KeepSource.manual)).length.run
  }

  def latestManualKeepTime(userId: Id[User])(implicit session: RSession): Option[DateTime] = {
    rows.filter(k => k.userId === userId && k.source.inSet(KeepSource.manual)).map(_.keptAt).max.run
  }

  // TODO (this hardcodes keeper and mobile sources - update to use the Set[KeepSource]
  def getCountByUserAndSource(userId: Id[User], sources: Set[KeepSource])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val sql = sql"select count(*) from bookmark where user_id=$userId and state = '#${KeepStates.ACTIVE}' and source IN ('keeper','mobile')"
    sql.as[Int].first
  }

  def getCountByUsers(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int] = {
    countCache.bulkGetOrElse(userIds.map(KeepCountKey(_))) { missingKeys =>
      val missingUserIds = missingKeys.map(_.userId)
      val missingCounts = (for (r <- rows if r.userId.inSet(missingUserIds) && r.state === KeepStates.ACTIVE) yield r).groupBy(_.userId).map {
        case (userId, keeps) => (userId, keeps.length)
      }.run
      missingCounts.map { case (userId, count) => KeepCountKey(userId) -> count }.toMap
    }
  }.map { case (key, count) => key.userId -> count }

  def getCountByUsersAndSource(userIds: Set[Id[User]], sources: Set[KeepSource])(implicit session: RSession): Map[Id[User], Int] = {
    (for (r <- rows if r.userId.inSet(userIds) && r.source.inSet(sources) && r.state === KeepStates.ACTIVE) yield r).groupBy(_.userId).map {
      case (userId, keeps) => (userId, keeps.length)
    }.run
  }.map { case (userId, count) => userId -> count }.toMap

  def getPrivatePublicCountByUser(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val sql = sql"select sum(is_private), sum(1 - is_private) from bookmark where user_id=${userId} and state = '#${KeepStates.ACTIVE}'"
    sql.as[(Int, Int)].first
  }

  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = sql"select count(*) as c from bookmark where updated_at between ${from} and ${to} and state='#${KeepStates.ACTIVE}';"
    sql.as[Int].first
  }

  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = sql"select count(*) as c from bookmark b where b.state = '#${KeepStates.ACTIVE}' and b.source=${source} and updated_at between ${from} and ${to};"
    sql.as[Int].first
  }

  def getAllCountsByTimeAndSource(from: DateTime, to: DateTime)(implicit session: RSession): Seq[(KeepSource, Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = sql"""select source, count(*) from bookmark b
      where b.state = '#${KeepStates.ACTIVE}' and created_at between ${from} and ${to}
      group by b.source;"""
    sql.as[(KeepSource, Int)].list
  }

  def getPrivateCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = sql"select count(*) as c from bookmark b where b.state = '#${KeepStates.ACTIVE}' and b.is_private = 1 and b.source=${source} and created_at between ${from} and ${to};"
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

  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (b <- rows if b.uriId === uriId && b.state === KeepStates.ACTIVE) yield b).firstOption.isDefined
  }

  def getSourcesByUser()(implicit session: RSession): Map[Id[User], Seq[KeepSource]] =
    StaticQuery.queryNA[(Long, String)]("""select distinct user_id, source from bookmark where state != '#${BookmarkStates.INACTIVE}'""").list.map {
      case (id, source) => (Id[User](id), KeepSource.get(source))
    }.groupBy(_._1).mapValues(_.map(_._2))

  def getLatestKeepsURIByUser(userId: Id[User], limit: Int, includePrivate: Boolean = false)(implicit session: RSession): Seq[Id[NormalizedURI]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = if (includePrivate) sql"select uri_Id from bookmark where state = '#${KeepStates.ACTIVE}' and user_id=${userId} order by kept_at DESC limit ${limit}"
    else sql"select uri_Id from bookmark where state = '#${KeepStates.ACTIVE}' and user_id=${userId} and is_private = false order by kept_at DESC limit ${limit}"

    sql.as[Id[NormalizedURI]].list
  }

  def getKeepExports(userId: Id[User])(implicit session: RSession): Seq[KeepExport] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val sqlQuery = sql"""select k.kept_at, k.title, k.url, group_concat(c.name)
      from bookmark k left join keep_to_collection kc
      on kc.bookmark_id = k.id left join collection c on c.id = kc.collection_id where k.user_id = ${userId} and k.state = '#${KeepStates.ACTIVE}'
      group by url order by k.id desc"""
    sqlQuery.as[(DateTime, Option[String], String, Option[String])].list.map { case (kept_at, title, url, tags) => KeepExport(kept_at, title, url, tags) }
  }

  // Make compiled in Slick 2.1

  def getByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeSet: Set[State[Keep]])(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.libraryId === libraryId && !b.state.inSet(excludeSet)) yield b).sortBy(r => (r.keptAt desc, r.id desc)).drop(offset).take(limit).list
  }

  def getByLibraryWithInconsistentOrgId(libraryId: Id[Library], expectedOrgId: Option[Id[Organization]], limit: Limit)(implicit session: RSession): Set[Id[Keep]] = {
    expectedOrgId match {
      case None => (for (b <- rows if b.libraryId === libraryId && b.organizationId.isDefined) yield b.id).take(limit.value).list.toSet
      case Some(orgId) => (for (b <- rows if b.libraryId === libraryId && (b.organizationId.isEmpty || b.organizationId =!= orgId)) yield b.id).take(limit.value).list.toSet
    }
  }

  def getCountByLibrary(libraryId: Id[Library])(implicit session: RSession): Int = {
    getCountsByLibrary(Set(libraryId)).getOrElse(libraryId, 0)
  }

  def getCountsByLibrary(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Int] = {
    val map = countByLibraryCache.bulkGetOrElse(libraryIds.map(CountByLibraryKey(_))) { missingKeys =>
      val missingLibraryIds = missingKeys.map(_.id)
      val keepsQuery = (for (b <- rows if b.libraryId.inSet(missingLibraryIds) && b.state === KeepStates.ACTIVE) yield b)
      val countQuery = keepsQuery.groupBy(_.libraryId).map { case (libraryId, keeps) => (libraryId, keeps.length) }
      countQuery.run.map { case (libraryIdOpt, keepCount) => (CountByLibraryKey(libraryIdOpt.get), keepCount) }.toMap
    }.map { case (CountByLibraryKey(libraryId), keepCount) => (libraryId, keepCount) }
    libraryIds.map { libId => libId -> map.getOrElse(libId, 0) }.toMap
  }

  def getByExtIdandLibraryId(extId: ExternalId[Keep], libraryId: Id[Library], excludeSet: Set[State[Keep]])(implicit session: RSession): Option[Keep] = {
    // TODO(ryan): deprecate ASAP
    getByExtId(extId, excludeSet).flatMap { keep =>
      if (!keep.libraryId.contains(libraryId)) {
        log.info(s"[KTL] unexpected keep location: $keep not in library $libraryId")
        None
      } else {
        Some(keep)
      }
    }
  }

  def getKeepsFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[Keep] = {
    (for (b <- rows if b.libraryId === library && b.state === KeepStates.ACTIVE && b.keptAt > since) yield b).sortBy(b => (b.keptAt asc, b.id)).take(max).list
  }

  def librariesWithMostKeepsSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select b.library_id, count(*) as cnt from bookmark b, library l where l.id = b.library_id and l.state='active' and l.visibility='published' and b.kept_at > $since group by b.library_id order by count(*) desc, b.library_id asc limit $count""".as[(Id[Library], Int)].list
  }

  def latestKeptAtByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[DateTime]] = {
    val keepsGroupedByLibrary = (for (r <- rows if r.libraryId.inSet(libraryIds) && r.state === KeepStates.ACTIVE) yield r).groupBy(_.libraryId)
    val map = keepsGroupedByLibrary.map { case (libraryId, keeps) => (libraryId, keeps.map(k => k.keptAt).max) }.list
      .collect {
        case (Some(libraryId), maxKeptAt) =>
          (libraryId, maxKeptAt)
      }.toMap
    libraryIds.map { libId => libId -> map.getOrElse(libId, None) }.toMap
  }

  def getKeepsByTimeWindow(uriId: Id[NormalizedURI], url: String, keptAfter: DateTime, keptBefore: DateTime)(implicit session: RSession): Set[Keep] = {
    val keeps = for { r <- rows if r.uriId === uriId && r.url === url && r.keptAt > keptAfter && r.keptAt < keptBefore } yield r
    keeps.list.toSet
  }

  def getRecentKeepsFromFollowedLibraries(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]])(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import scala.collection.JavaConversions._

    (beforeIdOpt.flatMap(getOpt), afterIdOpt.flatMap(getOpt)) match {
      case (Some(before), _) =>
        sql"""SELECT #$bookmarkColumnOrder FROM bookmark bm WHERE library_id IN (SELECT library_id FROM library_membership WHERE user_id=$userId AND state='active') AND state='active' AND user_id!=$userId AND kept_at <= ${before.keptAt} AND id < ${before.id.get} ORDER BY kept_at DESC, id DESC LIMIT $limit;""".as[Keep].list
      case (None, Some(after)) =>
        // This case is not strictly correct. It's not possible to call after a keep, and get other keeps kept in the same ms.
        // Fortunately, ending in this state where you have a keep id and need ones that happened after (and they happened in the same ms)
        // is nearly impossible. We can't use IDs as tie breakers because old IDs may get updated kept_at fields.
        sql"""SELECT #$bookmarkColumnOrder FROM bookmark bm WHERE library_id IN (SELECT library_id FROM library_membership WHERE user_id=$userId AND state='active') AND state='active' AND user_id!=$userId AND kept_at > ${after.keptAt} ORDER BY kept_at DESC, id DESC LIMIT $limit;""".as[Keep].list
      case (None, None) =>
        sql"""SELECT #$bookmarkColumnOrder FROM bookmark bm WHERE library_id IN (SELECT library_id FROM library_membership WHERE user_id=$userId AND state='active') AND state='active' AND user_id!=$userId ORDER BY kept_at DESC, id DESC LIMIT $limit;""".as[Keep].list
    }
  }

  def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (libIds.isEmpty) {
      Map()
    } else {
      val idset = libIds.map { _.id }.mkString("(", ",", ")")
      val q = sql"""select library_id, max(seq) from bookmark where library_id in #${idset} group by library_id"""

      q.as[(Long, Long)].list.map { case (libId, seq) => Id[Library](libId) -> SequenceNumber[Keep](seq) }.toMap
    }
  }

  def recentKeepNotes(libId: Id[Library], limit: Int)(implicit session: RSession): Seq[String] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select note from bookmark where library_id = $libId and note is not null order by updated_at desc limit $limit"""
    q.as[String].list
  }

  def getByLibraryIdAndExcludingVisibility(libId: Id[Library], excludeVisibility: Option[LibraryVisibility], limit: Int)(implicit session: RSession): Seq[Keep] = {
    val q = { for (r <- rows if r.libraryId === libId && r.visibility =!= excludeVisibility.orNull) yield r }.take(limit)
    q.list
  }

  def getKeepSourcesByUser(userId: Id[User])(implicit session: RSession): Seq[KeepSource] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select distinct source from bookmark where user_id=$userId and state='active'"""
    q.as[KeepSource].list
  }

}
