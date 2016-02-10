package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ LibraryMetadataCache, LibraryMetadataKey }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.discussion.Message
import org.joda.time.DateTime
import com.keepit.model.FeedFilter._

import scala.slick.jdbc.{ GetResult, PositionedResult }

@ImplementedBy(classOf[KeepRepoImpl])
trait KeepRepo extends Repo[Keep] with ExternalIdColumnFunction[Keep] with SeqNumberFunction[Keep] {
  def getOption(id: Id[Keep], excludeStates: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def getByIds(ids: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Keep]
  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Keep]])(implicit session: RSession): Seq[Keep]
  def getByExtId(extId: ExternalId[Keep], excludeStates: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def getByExtIds(extIds: Set[ExternalId[Keep]])(implicit session: RSession): Map[ExternalId[Keep], Option[Keep]]
  def getByExtIdandLibraryId(extId: ExternalId[Keep], libraryId: Id[Library], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] // TODO(ryan)[2015-08-03]: deprecate ASAP!
  def getByIdGreaterThan(lowerBoundId: Id[Keep], limit: Int)(implicit session: RSession): Seq[Keep]

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] //todo: replace option with seq
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
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
  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int
  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getAllCountsByTimeAndSource(from: DateTime, to: DateTime)(implicit session: RSession): Seq[(KeepSource, Int)]
  def getPrivateCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getBookmarksChanged(num: SequenceNumber[Keep], fetchSize: Int)(implicit session: RSession): Seq[Keep]
  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
  def getLatestKeepsURIByUser(userId: Id[User], limit: Int, includePrivate: Boolean = false)(implicit session: RSession): Seq[Id[NormalizedURI]]
  def getKeepExports(userId: Id[User])(implicit session: RSession): Seq[KeepExport]
  def latestManualKeepTime(userId: Id[User])(implicit session: RSession): Option[DateTime]
  def getKeepsByTimeWindow(uriId: Id[NormalizedURI], url: String, keptAfter: DateTime, keptBefore: DateTime)(implicit session: RSession): Set[Keep]
  def getKeepSourcesByUser(userId: Id[User])(implicit session: RSession): Seq[KeepSource]
  def orgsWithKeeps()(implicit session: RSession): Seq[(Id[Organization], Int)]

  // TODO(ryan): All of these methods are going to have to migrate to KeepToLibraryRepo
  def getByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getCountByLibrary(libraryId: Id[Library])(implicit session: RSession): Int
  def getCountByLibrariesSince(libraryIds: Set[Id[Library]], since: DateTime)(implicit session: RSession): Int
  def getCountsByLibrary(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Int]
  def getByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[Keep]
  def recentKeepNotes(libId: Id[Library], limit: Int)(implicit session: RSession): Seq[String]
  def getByLibraryIdsAndUriIds(libraryIds: Set[Id[Library]], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[Keep]
  def getByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def getByLibraryIdAndExcludingVisibility(libId: Id[Library], excludeVisibility: Option[LibraryVisibility], limit: Int)(implicit session: RSession): Seq[Keep]
  def getByLibraryWithInconsistentOrgId(libraryId: Id[Library], expectedOrgId: Option[Id[Organization]], limit: Limit)(implicit session: RSession): Set[Id[Keep]]
  def getKeepsFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[Keep]
  def getRecentKeeps(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]], filterOpt: Option[ShoeboxFeedFilter])(implicit session: RSession): Seq[(Keep, DateTime)]
  def getRecentKeepsByActivity(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]], filterOpt: Option[ShoeboxFeedFilter] = None)(implicit session: RSession): Seq[(Keep, DateTime)]
  def librariesWithMostKeepsSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)]
  def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]]
  def latestKeptAtByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[DateTime]]
  def deactivate(model: Keep)(implicit session: RWSession): Keep
}

@Singleton
class KeepRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    libraryMembershipRepo: LibraryMembershipRepo, // implicit dependency on this repo via a plain SQL query getRecentKeeps
    organizationMembershipRepo: OrganizationMembershipRepo, // implicit dependency on this repo via a plain SQL query getRecentKeeps
    keepToLibraryRepo: KeepToLibraryRepo, // implicit dependency on this repo via a plain SQL query getRecentKeeps
    keepToUserRepo: KeepToUserRepo, // implicit dependency on this repo via a plain SQL query getRecentKeeps
    countCache: KeepCountCache,
    keepByIdCache: KeepByIdCache,
    basicKeepByIdCache: BasicKeepByIdCache,
    keepUriUserCache: KeepUriUserCache,
    libraryMetadataCache: LibraryMetadataCache,
    countByLibraryCache: CountByLibraryCache) extends DbRepo[Keep] with KeepRepo with SeqNumberDbFunction[Keep] with ExternalIdColumnDbFunction[Keep] with Logging {

  import db.Driver.simple._

  type First = (Option[Id[Keep]], // id
  DateTime, // createdAt
  DateTime, // updatedAt
  State[Keep], // state
  SequenceNumber[Keep], // seq
  ExternalId[Keep], // externalId
  Option[String], // title
  Option[String], // note
  Id[NormalizedURI], // uriId
  String // url
  )
  type Rest = (Option[Id[User]], // userId
  Option[Id[User]], // originalKeeperId
  KeepSource, // source
  DateTime, // keptAt
  DateTime, // lastActivityAt
  Option[SequenceNumber[Message]], // messageSeq
  KeepConnections, // connections
  Option[Id[Library]], // libraryId
  LibraryVisibility, // visibility
  Option[Id[Organization]], // organizationId
  Option[Boolean], // isPrimary
  LibrariesHash, // librariesHash
  ParticipantsHash // participantsHash
  )
  def fromDbRow(first: First, rest: Rest): Keep = (first, rest) match {
    case ((id: Option[Id[Keep]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[Keep],
      seq: SequenceNumber[Keep],
      externalId: ExternalId[Keep],
      title: Option[String],
      note: Option[String],
      uriId: Id[NormalizedURI],
      url: String), (
      userId: Option[Id[User]],
      originalKeeperId: Option[Id[User]],
      source: KeepSource,
      keptAt: DateTime,
      lastActivityAt: DateTime,
      messageSeq: Option[SequenceNumber[Message]],
      connections: KeepConnections,
      libraryId: Option[Id[Library]],
      visibility: LibraryVisibility,
      organizationId: Option[Id[Organization]],
      // These fields are discarded, they are DB-only
      isPrimary: Option[Boolean],
      lh: LibrariesHash,
      ph: ParticipantsHash)) =>
      Keep(
        id,
        createdAt,
        updatedAt,
        state,
        seq,
        externalId,
        title,
        note,
        uriId,
        url,
        userId,
        originalKeeperId orElse userId,
        source,
        keptAt,
        lastActivityAt,
        messageSeq,
        connections,
        libraryId,
        visibility,
        organizationId
      )
  }

  def toDbRow(k: Keep) = {
    Some((
      k.id,
      k.createdAt,
      k.updatedAt,
      k.state,
      k.seq,
      k.externalId,
      k.title,
      k.note,
      k.uriId,
      k.url),
      (k.userId,
        k.originalKeeperId orElse k.userId,
        k.source,
        k.keptAt,
        k.lastActivityAt,
        k.messageSeq,
        k.connections,
        k.libraryId,
        k.visibility,
        k.organizationId,
        if (k.isActive) Some(true) else None,
        k.connections.librariesHash,
        k.connections.participantsHash
      ))
  }

  type RepoImpl = KeepTable
  class KeepTable(tag: Tag) extends RepoTable[Keep](db, tag, "bookmark") with ExternalIdColumn[Keep] with SeqNumberColumn[Keep] with NamedColumns {
    def title = column[Option[String]]("title", O.Nullable) //indexd
    def note = column[Option[String]]("note", O.Nullable)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull) //indexd
    def url = column[String]("url", O.NotNull) //indexd
    def userId = column[Option[Id[User]]]("user_id", O.Nullable) //indexd
    def originalKeeperId = column[Option[Id[User]]]("original_keeper_id", O.Nullable)
    def source = column[KeepSource]("source", O.NotNull)
    def keptAt = column[DateTime]("kept_at", O.NotNull)
    def lastActivityAt = column[DateTime]("last_activity_at", O.NotNull)
    def messageSeq = column[Option[SequenceNumber[Message]]]("message_seq", O.Nullable)
    def connections = column[KeepConnections]("connections", O.NotNull)
    def libraryId = column[Option[Id[Library]]]("library_id", O.Nullable)
    def visibility = column[LibraryVisibility]("visibility", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)

    // Used only within the DB to ensure integrity and make queries more efficient
    def isPrimary = column[Option[Boolean]]("is_primary", O.Nullable) // trueOrNull
    def librariesHash = column[LibrariesHash]("libraries_hash", O.NotNull)
    def participantsHash = column[ParticipantsHash]("participants_hash", O.NotNull)

    def * = (
      (id.?, createdAt, updatedAt, state, seq, externalId, title, note, uriId, url),
      (userId, originalKeeperId, source, keptAt, lastActivityAt, messageSeq,
        connections, libraryId, visibility, organizationId,
        isPrimary, librariesHash, participantsHash)
    ).shaped <> ((fromDbRow _).tupled, toDbRow)

    def isPrivate: Column[Boolean] = {
      val privateVisibilities: Set[LibraryVisibility] = Set(LibraryVisibility.SECRET, LibraryVisibility.ORGANIZATION)
      visibility.inSet(privateVisibilities)
    }
  }

  def table(tag: Tag) = new KeepTable(tag)
  initTable()

  private def activeRows = rows.filter(_.state === KeepStates.ACTIVE)

  implicit val getBookmarkSourceResult = getResultFromMapper[KeepSource]
  implicit val setBookmarkSourceParameter = setParameterFromMapper[KeepSource]

  implicit val getConnectionsResult = getResultFromMapper[KeepConnections]
  implicit val getLibrariesHashResult = getResultFromMapper[LibrariesHash]
  implicit val getParticipantsHashResult = getResultFromMapper[ParticipantsHash]

  private implicit val getBookmarkResult: GetResult[com.keepit.model.Keep] = GetResult { r: PositionedResult => // bonus points for anyone who can do this generically in Slick 2.0
    fromDbRow(
      (r.<<[Option[Id[Keep]]],
        r.<<[DateTime],
        r.<<[DateTime],
        r.<<[State[Keep]],
        r.<<[SequenceNumber[Keep]],
        r.<<[ExternalId[Keep]],
        r.<<[Option[String]],
        r.<<[Option[String]],
        r.<<[Id[NormalizedURI]],
        r.<<[String]),
      (r.<<[Option[Id[User]]],
        r.<<[Option[Id[User]]],
        r.<<[KeepSource],
        r.<<[DateTime],
        r.<<[DateTime],
        r.<<[Option[SequenceNumber[Message]]],
        r.<<[KeepConnections],
        r.<<[Option[Id[Library]]],
        r.<<[LibraryVisibility],
        r.<<[Option[Id[Organization]]],
        r.<<[Option[Boolean]],
        r.<<[LibrariesHash],
        r.<<[ParticipantsHash])
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

  def deactivate(model: Keep)(implicit session: RWSession) = {
    save(model.sanitizeForDelete)
  }

  def page(page: Int, size: Int, includePrivate: Boolean, excludeStates: Set[State[Keep]])(implicit session: RSession): Seq[Keep] = {
    val q = for {
      t <- rows if (t.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility) || includePrivate) && !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  override def deleteCache(keep: Keep)(implicit session: RSession): Unit = {
    keep.libraryId foreach { id =>
      countByLibraryCache.remove(CountByLibraryKey(id))
      libraryMetadataCache.remove(LibraryMetadataKey(id))
    }
    keep.id.foreach { keepId =>
      keepByIdCache.remove(KeepIdKey(keepId))
      basicKeepByIdCache.remove(BasicKeepIdKey(keepId))
    }
    keep.userId.foreach { userId =>
      keepUriUserCache.remove(KeepUriUserKey(keep.uriId, userId))
      countCache.remove(KeepCountKey(userId))
    }
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
      basicKeepByIdCache.remove(BasicKeepIdKey(keep.id.get))
      keep.userId.foreach { userId =>
        keepUriUserCache.set(KeepUriUserKey(keep.uriId, userId), keep)
        countCache.remove(KeepCountKey(userId))
      }
    }
  }

  override def get(id: Id[Keep])(implicit session: RSession): Keep = {
    keepByIdCache.getOrElse(KeepIdKey(id)) {
      getCompiled(id).firstOption.getOrElse(throw NotFoundException(id))
    }
  }

  def getOption(id: Id[Keep], excludeStates: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] = {
    keepByIdCache.getOrElseOpt(KeepIdKey(id)) {
      getCompiled(id).firstOption.filter(keep => !excludeStates.contains(keep.state))
    }
  }

  def orgsWithKeeps()(implicit session: RSession): Seq[(Id[Organization], Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select organization_id, count(*) from bookmark where organization_id is not null and organization_id != 9 and organization_id not in (select organization_id from organization_experiment where state = 'inactive' or experiment_type = 'fake') and state = 'active' and kept_at > DATE_SUB(NOW(), INTERVAL 1 DAY)  group by organization_id order by count(*) desc"""
    val res = q.as[(Long, Int)].list
    res.map { case (orgId, count) => Id[Organization](orgId) -> count }
  }

  def getByIds(ids: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Keep] = {
    keepByIdCache.bulkGetOrElse(ids.map(KeepIdKey)) { missingKeys =>
      val missingIds = missingKeys.map(_.id)
      activeRows.filter(_.id.inSet(missingIds)).list.map { k => KeepIdKey(k.id.get) -> k }.toMap
    }.map { case (k, v) => k.id -> v }
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

  def getByUriAndLibrariesHash(uriId: Id[NormalizedURI], librariesHash: LibrariesHash)(implicit session: RSession): Set[Keep] = {
    (for (b <- rows if b.uriId === uriId && b.librariesHash === librariesHash && b.state === KeepStates.ACTIVE) yield b).list.toSet
  }

  // preserved for backward compatibility
  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] =
    keepUriUserCache.getOrElseOpt(KeepUriUserKey(uriId, userId)) {
      val keeps = (for (b <- rows if b.uriId === uriId && b.userId === userId && b.state === KeepStates.ACTIVE) yield b).list
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

  def getByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep] = {
    (for (b <- rows if b.uriId === uriId && b.libraryId === libId && b.state =!= excludeState.orNull) yield b).firstOption
  }

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).list

  def getByUser(userId: Id[User], excludeSet: Set[State[Keep]])(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.userId === userId && !b.state.inSet(excludeSet)) yield b).sortBy(_.keptAt).list

  def getNonPrivate(ownerId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val interpolated = sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${ownerId} and bm.state = '#${KeepStates.ACTIVE}' and bm.visibility != '#${LibraryVisibility.SECRET.value}' order by bm.kept_at desc, bm.id desc limit $offset, $limit;"""
    interpolated.as[Keep].list
  }

  def getPrivate(ownerId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val interpolated = sql"""select #$bookmarkColumnOrder from bookmark bm where bm.user_id = ${ownerId} and bm.state = '#${KeepStates.ACTIVE}' and bm.visibility = '#${LibraryVisibility.SECRET.value}' order by bm.kept_at desc, bm.id desc limit $offset, $limit;"""
    interpolated.as[Keep].list
  }

  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

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
      }.list
      missingCounts.collect { case (Some(userId), count) => KeepCountKey(userId) -> count }.toMap
    }
  }.map { case (key, count) => key.userId -> count }

  def getCountByUsersAndSource(userIds: Set[Id[User]], sources: Set[KeepSource])(implicit session: RSession): Map[Id[User], Int] = {
    (for (r <- rows if r.userId.inSet(userIds) && r.source.inSet(sources) && r.state === KeepStates.ACTIVE) yield r).groupBy(_.userId).map {
      case (userId, keeps) => (userId, keeps.length)
    }.list
  }.collect { case (Some(userId), count) => userId -> count }.toMap

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

    val sql = sql"select count(*) as c from bookmark b where b.state = '#${KeepStates.ACTIVE}' and b.visibility = 'secret' and b.source=${source} and b.created_at between ${from} and ${to};"
    sql.as[Int].first
  }

  def getBookmarksChanged(num: SequenceNumber[Keep], limit: Int)(implicit session: RSession): Seq[Keep] = super.getBySequenceNumber(num, limit)

  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (b <- rows if b.uriId === uriId && b.state === KeepStates.ACTIVE) yield b).firstOption.isDefined
  }

  def getLatestKeepsURIByUser(userId: Id[User], limit: Int, includePrivate: Boolean = false)(implicit session: RSession): Seq[Id[NormalizedURI]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val sql = if (includePrivate) sql"select uri_Id from bookmark where state = '#${KeepStates.ACTIVE}' and user_id=${userId} order by kept_at DESC limit ${limit}"
    else sql"select uri_Id from bookmark where state = '#${KeepStates.ACTIVE}' and user_id=${userId} and (visibility = 'discoverable' or visibility = 'published') order by kept_at DESC limit ${limit}"

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

  def getCountByLibrariesSince(libraryIds: Set[Id[Library]], since: DateTime)(implicit session: RSession): Int = {
    rows.filter(k => k.keptAt > since && k.libraryId.inSet(libraryIds)).length.run
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
    getByExtId(extId, excludeSet).filter { keep => keep.libraryId.contains(libraryId) }
  }

  def getByIdGreaterThan(lowerBoundId: Id[Keep], limit: Int)(implicit session: RSession): Seq[Keep] = {
    val q = for { t <- rows if t.id > lowerBoundId } yield t
    q.sortBy(_.id asc).take(limit).list
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

  def getRecentKeeps(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]], filterOpt: Option[ShoeboxFeedFilter] = None)(implicit session: RSession): Seq[(Keep, DateTime)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val ktl_JOIN_lm_WHERE_THIS_USER = s"""keep_to_library ktl inner join library_membership lm on (ktl.library_id = lm.library_id) where lm.state = 'active' and ktl.state = 'active' and lm.user_id = $userId"""
    val ktl_JOIN_om_WHERE_THIS_USER = s"""keep_to_library ktl inner join organization_membership om on (ktl.organization_id = om.organization_id) where om.state = 'active' and ktl.state = 'active' and (ktl.visibility = 'published' or ktl.visibility = 'organization') and om.user_id = $userId"""
    val ktu_WHERE_THIS_USER = s"""keep_to_user ktu where ktu.state = 'active' and ktu.user_id = $userId"""

    def getFirstAddedAt(keepId: Id[Keep]): Option[DateTime] = {
      sql"""SELECT min(k.added_at) FROM
        ((SELECT ktl.added_at as added_at FROM #$ktl_JOIN_lm_WHERE_THIS_USER AND ktl.keep_id = $keepId)
        UNION (SELECT ktl.added_at as added_at FROM #$ktl_JOIN_om_WHERE_THIS_USER AND ktl.keep_id = $keepId)
        UNION (SELECT ktu.added_at as added_at FROM #$ktu_WHERE_THIS_USER AND ktu.keep_id = $keepId)) as k
      """.as[Option[DateTime]].first
    }

    def getKeepIdAndFirstAddedAt(id: ExternalId[Keep]): Option[(Id[Keep], DateTime)] = {
      for {
        keep <- getOpt(id)
        keepId <- keep.id
        firstAddedAtForUser <- getFirstAddedAt(keepId)
      } yield (keepId, firstAddedAtForUser)
    }

    val added_at_BEFORE = beforeIdOpt.flatMap(getKeepIdAndFirstAddedAt) match {
      case None => "true"
      case Some((keepId, before)) => s"added_at <= '$before' AND keep_id < $keepId"
    }

    val added_at_AFTER = afterIdOpt.flatMap(getKeepIdAndFirstAddedAt) match {
      case None => "true"
      case Some((_, after)) => s"added_at > '$after'"
      // This is not strictly correct. It's not possible to call after a keep, and get other keeps kept in the same ms.
      // Fortunately, ending in this state where you have a keep id and need ones that happened after (and they happened in the same ms)
      // is nearly impossible. We can't use IDs as tie breakers because old IDs may get updated added_at fields.
    }

    val keepInOrgFilter = filterOpt.collect { case OrganizationKeeps(orgId) => s"""AND ktl.organization_id = $orgId""" }

    val keepsFromLibraries = s"""SELECT ktl.keep_id as id, min(ktl.added_at) as first_added_at FROM $ktl_JOIN_lm_WHERE_THIS_USER AND $added_at_BEFORE GROUP BY ktl.keep_id"""
    val keepsFromOrganizations = s"""SELECT ktl.keep_id as id, min(ktl.added_at) as first_added_at FROM $ktl_JOIN_om_WHERE_THIS_USER AND $added_at_BEFORE ${keepInOrgFilter.getOrElse("")} GROUP BY ktl.keep_id"""
    val keepsFromUser = s"""SELECT ktu.keep_id as id, ktu.added_at as first_added_at FROM $ktu_WHERE_THIS_USER AND $added_at_BEFORE"""

    val fromQuery = filterOpt match {
      case Some(OwnKeeps) => keepsFromUser
      case Some(OrganizationKeeps(orgId)) => keepsFromOrganizations
      case _ => s"""($keepsFromLibraries) UNION ($keepsFromOrganizations) UNION ($keepsFromUser)"""
    }

    val keepsAndFirstAddedAt = sql"""
      SELECT k.id as keep_id, min(k.first_added_at) as added_at
      FROM (#$fromQuery) as k
      GROUP BY k.id
      HAVING #$added_at_AFTER AND #$added_at_BEFORE
      ORDER BY added_at DESC, keep_id DESC
      LIMIT $limit
    """.as[(Id[Keep], DateTime)].list

    val shouldFilterByUser = filterOpt.contains(OwnKeeps)
    val keepIds = keepsAndFirstAddedAt.map { case (keepId, _) => keepId }
    val keepsById = getByIds(keepIds.toSet)
    keepsAndFirstAddedAt.map { case (keepId, firstAddedAt) => keepsById(keepId) -> firstAddedAt }
      .filter { case (keep, _) => !shouldFilterByUser || keep.userId.contains(userId) }
  }

  def getRecentKeepsByActivity(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]], filterOpt: Option[ShoeboxFeedFilter] = None)(implicit session: RSession): Seq[(Keep, DateTime)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val ktl_JOIN_lm_WHERE_THIS_USER = s"""keep_to_library ktl inner join library_membership lm on (ktl.library_id = lm.library_id) where lm.state = 'active' and ktl.state = 'active' and lm.user_id = $userId"""
    val ktl_JOIN_om_WHERE_THIS_USER = s"""keep_to_library ktl inner join organization_membership om on (ktl.organization_id = om.organization_id) where om.state = 'active' and ktl.state = 'active' and (ktl.visibility = 'published' or ktl.visibility = 'organization') and om.user_id = $userId"""
    val ktu_WHERE_THIS_USER = s"""keep_to_user ktu where ktu.state = 'active' and ktu.user_id = $userId"""

    def getLastActivityAt(keepId: Id[Keep]): Option[DateTime] = sql"""SELECT last_activity_at from bookmark where id = $keepId""".as[Option[DateTime]].first

    def getKeepIdAndLastActivityAt(id: ExternalId[Keep]): Option[(Id[Keep], DateTime)] = {
      for {
        keep <- getOpt(id)
        keepId = keep.id.get
        lastActivityAtForKeep <- getLastActivityAt(keepId)
      } yield (keepId, lastActivityAtForKeep)
    }

    val last_activity_at_BEFORE = beforeIdOpt.flatMap(getKeepIdAndLastActivityAt) match {
      case None => (tableName: String) => "true"
      case Some((keepId, before)) =>
        (tableName: String) => {
          if (tableName == "k") s"last_activity_at < '$before' AND k.id < $keepId"
          else s"last_activity_at < '$before' AND $tableName.keep_id < $keepId"
        }
    }

    val last_activity_at_AFTER = afterIdOpt.flatMap(getKeepIdAndLastActivityAt) match {
      case None => (tableName: String) => "true"
      case Some((keepId, after)) =>
        (tableName: String) => {
          if (tableName == "k") s"last_activity_at > '$after' AND k.id > $keepId"
          else s"last_activity_at > '$after' AND $tableName.keep_id > $keepId"
        }
    }

    val keepInOrgFilter = filterOpt.collect { case OrganizationKeeps(orgId) => s"""AND ktl.organization_id = $orgId""" }

    val keepsFromLibraries = s"""SELECT ktl.keep_id as id, min(ktl.last_activity_at) as most_recent_activity FROM $ktl_JOIN_lm_WHERE_THIS_USER AND ${last_activity_at_BEFORE("ktl")} GROUP BY ktl.keep_id"""
    val keepsFromOrganizations = s"""SELECT ktl.keep_id as id, min(ktl.last_activity_at) as most_recent_activity FROM $ktl_JOIN_om_WHERE_THIS_USER AND ${last_activity_at_BEFORE("ktl")} ${keepInOrgFilter.getOrElse("")} GROUP BY ktl.keep_id"""
    val keepsFromUser = s"""SELECT ktu.keep_id as id, ktu.last_activity_at as most_recent_activity FROM $ktu_WHERE_THIS_USER AND ${last_activity_at_BEFORE("ktu")}"""

    val fromQuery = filterOpt match {
      case Some(OwnKeeps) => keepsFromUser
      case Some(OrganizationKeeps(orgId)) => keepsFromOrganizations
      case _ => s"""($keepsFromLibraries) UNION ($keepsFromOrganizations) UNION ($keepsFromUser)"""
    }

    val keepsAndLastActivityAt = sql"""
      SELECT k.id, max(k.most_recent_activity) as last_activity_at
      FROM (#$fromQuery) as k
      GROUP BY k.id
      HAVING #${last_activity_at_AFTER("k")} AND #${last_activity_at_BEFORE("k")}
      ORDER BY last_activity_at DESC, k.id DESC
      LIMIT $limit
    """.as[(Id[Keep], DateTime)].list

    val shouldFilterByUser = filterOpt.contains(OwnKeeps)
    val keepIds = keepsAndLastActivityAt.map { case (keepId, _) => keepId }
    val keepsById = getByIds(keepIds.toSet)
    keepsAndLastActivityAt.map { case (keepId, lastActivityAt) => keepsById(keepId) -> lastActivityAt }
      .filter { case (keep, _) => !shouldFilterByUser || keep.userId.contains(userId) }
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
