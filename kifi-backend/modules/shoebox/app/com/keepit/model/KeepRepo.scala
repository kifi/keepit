package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Provider, Singleton }
import com.keepit.commanders.{ KeepQuery, LibraryMetadataCache }
import com.keepit.common.core.{ anyExtensionOps, iterableExtensionOps }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.model.FeedFilter._
import com.keepit.shoebox.data.keep.{ KeepRecipientId, KeepProximitySection }
import org.joda.time.DateTime

import scala.slick.jdbc.{ GetResult, PositionedResult }

@ImplementedBy(classOf[KeepRepoImpl])
trait KeepRepo extends Repo[Keep] with ExternalIdColumnFunction[Keep] with SeqNumberFunction[Keep] {
  def saveAndIncrementSequenceNumber(model: Keep)(implicit session: RWSession): Keep // more expensive and deadlock-prone than `save`
  def getActive(id: Id[Keep])(implicit session: RSession): Option[Keep]
  def getActiveByIds(ids: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Keep]
  def getByExtId(extId: ExternalId[Keep], excludeStates: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def getByExtIds(extIds: Set[ExternalId[Keep]])(implicit session: RSession): Map[ExternalId[Keep], Option[Keep]]
  def getByExtIdandLibraryId(extId: ExternalId[Keep], libraryId: Id[Library], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Option[Keep]
  def getByIdGreaterThan(lowerBoundId: Id[Keep], limit: Int)(implicit session: RSession): Seq[Keep]

  def getByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Keep] //todo: replace option with seq
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getByUser(userId: Id[User], excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getByUser(userId: Id[User], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def getByUserAndCollection(userId: Id[User], collectionId: Id[Collection], beforeId: Option[ExternalId[Keep]], afterId: Option[ExternalId[Keep]], count: Int)(implicit session: RSession): Seq[Keep]
  def bulkGetByUserAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Map[Id[NormalizedURI], Keep]
  def getCountByUser(userId: Id[User])(implicit session: RSession): Int
  def getCountManualByUserInLastDays(userId: Id[User], days: Int)(implicit session: RSession): Int
  def getCountByUsers(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int]
  def getCountByUsersAndSource(userIds: Set[Id[User]], sources: Set[KeepSource])(implicit session: RSession): Map[Id[User], Int]
  def getCountByTime(from: DateTime, to: DateTime)(implicit session: RSession): Int
  def getCountByTimeAndSource(from: DateTime, to: DateTime, source: KeepSource)(implicit session: RSession): Int
  def getAllCountsByTimeAndSource(from: DateTime, to: DateTime)(implicit session: RSession): Seq[(KeepSource, Int)]
  def getBookmarksChanged(num: SequenceNumber[Keep], fetchSize: Int)(implicit session: RSession): Seq[Keep]
  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
  def getLatestKeepsURIByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[Id[NormalizedURI]]
  def getKeepExports(userId: Id[User])(implicit session: RSession): Seq[KeepExport]
  def latestManualKeepTime(userId: Id[User])(implicit session: RSession): Option[DateTime]
  def getKeepsByTimeWindow(uriId: Id[NormalizedURI], url: String, keptAfter: DateTime, keptBefore: DateTime)(implicit session: RSession): Set[Keep]
  def getKeepSourcesByUser(userId: Id[User])(implicit session: RSession): Seq[KeepSource]

  def getRecentKeepsByActivity(userId: Id[User], limit: Int, beforeIdOpt: Option[ExternalId[Keep]], afterIdOpt: Option[ExternalId[Keep]], filterOpt: Option[ShoeboxFeedFilter] = None)(implicit session: RSession): Seq[(Keep, DateTime)]
  def pageByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeSet: Set[State[Keep]] = Set(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep]
  def getKeepIdsForQuery(query: KeepQuery)(implicit session: RSession): Seq[Id[Keep]]
  def getSectionedKeepsOnUri(viewer: Id[User], uri: Id[NormalizedURI], seen: Set[Id[Keep]], limit: Int, filterRecipientOpt: Option[KeepRecipientId])(implicit session: RSession): Map[KeepProximitySection, Seq[Id[Keep]]]
  def getChangedKeepsFromLibrary(libraryId: Id[Library], seq: SequenceNumber[Keep])(implicit session: RSession): Seq[Keep]
  def getByUriAndLibrariesHash(uriId: Id[NormalizedURI], libIds: Set[Id[Library]])(implicit session: RSession): Seq[Keep]
  def getByUriAndParticipantsHash(uriId: Id[NormalizedURI], users: Set[Id[User]], emails: Set[EmailAddress])(implicit session: RSession): Seq[Keep]
  def getPersonalKeepsOnUris(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeAccess: Option[LibraryAccess])(implicit session: RSession): Map[Id[NormalizedURI], Set[Id[Keep]]]

  def deactivate(model: Keep)(implicit session: RWSession): Keep

  def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]]

  //admin
  def pageAscendingWithUserExcludingSources(fromId: Option[Id[Keep]], pageSize: Int, excludeStates: Set[State[Keep]] = Set(KeepStates.INACTIVE), excludeSources: Set[KeepSource])(implicit session: RSession): Seq[Keep]
}

@Singleton
class KeepRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    libMembershipRepoImpl: Provider[LibraryMembershipRepoImpl],
    orgMembershipRepoImpl: Provider[OrganizationMembershipRepoImpl],
    keepToLibraryRepoImpl: Provider[KeepToLibraryRepoImpl],
    keepToUserRepoImpl: Provider[KeepToUserRepoImpl],
    keepToEmailRepoImpl: Provider[KeepToEmailRepoImpl],
    countCache: KeepCountCache,
    keepByIdCache: KeepByIdCache,
    keepUriUserCache: KeepUriUserCache,
    libraryMetadataCache: LibraryMetadataCache,
    countByLibraryCache: CountByLibraryCache) extends DbRepo[Keep] with KeepRepo with SeqNumberDbFunction[Keep] with ExternalIdColumnDbFunction[Keep] with Logging {

  private lazy val ktlRows = keepToLibraryRepoImpl.get.activeRows
  private lazy val ktuRows = keepToUserRepoImpl.get.activeRows
  private lazy val kteRows = keepToEmailRepoImpl.get.activeRows
  private lazy val lmRows = libMembershipRepoImpl.get.activeRows
  private lazy val omRows = orgMembershipRepoImpl.get.activeRows
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
  KeepRecipients, // connections
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
      connections: KeepRecipients,
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
        connections
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
        k.recipients,
        if (k.isActive) Some(true) else None,
        k.recipients.librariesHash,
        k.recipients.participantsHash
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
    def connections = column[KeepRecipients]("connections", O.NotNull)

    // Used only within the DB to ensure integrity and make queries more efficient
    def isPrimary = column[Option[Boolean]]("is_primary", O.Nullable) // trueOrNull
    def librariesHash = column[LibrariesHash]("libraries_hash", O.NotNull)
    def participantsHash = column[ParticipantsHash]("participants_hash", O.NotNull)

    def * = (
      (id.?, createdAt, updatedAt, state, seq, externalId, title, note, uriId, url),
      (userId, originalKeeperId, source, keptAt, lastActivityAt, messageSeq,
        connections, isPrimary, librariesHash, participantsHash)
    ).shaped <> ((fromDbRow _).tupled, toDbRow)
  }

  def table(tag: Tag) = new KeepTable(tag)
  initTable()

  private def activeRows = rows.filter(_.state === KeepStates.ACTIVE)

  implicit val getBookmarkSourceResult = getResultFromMapper[KeepSource]
  implicit val setBookmarkSourceParameter = setParameterFromMapper[KeepSource]
  implicit val setSeqParameter = setParameterFromMapper[SequenceNumber[Keep]]

  implicit val getConnectionsResult = getResultFromMapper[KeepRecipients]
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
        r.<<[KeepRecipients],
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

  def saveAndIncrementSequenceNumber(model: Keep)(implicit session: RWSession): Keep = {
    super.save(model.copy(seq = sequence.incrementAndGet()))
  }

  def deactivate(model: Keep)(implicit session: RWSession) = {
    save(model.sanitizeForDelete)
  }

  override def deleteCache(keep: Keep)(implicit session: RSession): Unit = {
    keep.id.foreach { keepId =>
      keepByIdCache.remove(KeepIdKey(keepId))
    }
    keep.userId.foreach { userId =>
      keepUriUserCache.remove(KeepUriUserKey(keep.uriId, userId))
      countCache.remove(KeepCountKey(userId))
    }
  }

  override def invalidateCache(keep: Keep)(implicit session: RSession): Unit = {
    if (keep.state == KeepStates.INACTIVE) {
      deleteCache(keep)
    } else {
      keepByIdCache.set(KeepIdKey(keep.id.get), keep)
      keep.userId.foreach { userId =>
        keepUriUserCache.set(KeepUriUserKey(keep.uriId, userId), keep)
        countCache.remove(KeepCountKey(userId))
      }
    }
  }

  override def get(id: Id[Keep])(implicit session: RSession): Keep = {
    val cacheKeep = keepByIdCache.get(KeepIdKey(id))
    cacheKeep.getOrElse {
      val internalKeep = getCompiled(id).firstOption.getOrElse(throw NotFoundException(id))
      if (internalKeep.isActive) keepByIdCache.set(KeepIdKey(id), internalKeep)
      internalKeep
    }
  }

  def getActive(id: Id[Keep])(implicit session: RSession): Option[Keep] = {
    keepByIdCache.getOrElseOpt(KeepIdKey(id)) {
      getCompiled(id).firstOption.filter(_.isActive)
    }
  }

  def getActiveByIds(ids: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Keep] = {
    keepByIdCache.bulkGetOrElse(ids.map(KeepIdKey)) { missingKeys =>
      val missingIds = missingKeys.map(_.id)
      activeRows.filter(_.id.inSet(missingIds)).list.map { k => KeepIdKey(k.id.get) -> k }.toMap
    }.collect { case (k, v) if v.isActive => k.id -> v }
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
      val keeps = (for (b <- rows if b.uriId === uriId && b.userId === userId && b.state === KeepStates.ACTIVE) yield b).list
      if (keeps.length > 1) log.warn(s"[getByUriAndUser] ${keeps.length} keeps found for (uri, user) pair ${(uriId, userId)}")
      keeps.headOption
    }

  def getByUriAndLibrariesHash(uriId: Id[NormalizedURI], libIds: Set[Id[Library]])(implicit session: RSession): Seq[Keep] = {
    val hash = LibrariesHash(libIds)
    activeRows.filter(k => k.uriId === uriId && k.librariesHash === hash).list.filter(_.recipients.libraries == libIds)
  }

  def getByUriAndParticipantsHash(uriId: Id[NormalizedURI], users: Set[Id[User]], emails: Set[EmailAddress])(implicit session: RSession): Seq[Keep] = {
    val userHash = ParticipantsHash(users)
    activeRows.filter(k => k.uriId === uriId && k.participantsHash === userHash).list.filter(k => k.recipients.users == users && k.recipients.emails == emails)
  }

  def getPersonalKeepsOnUris(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeAccess: Option[LibraryAccess])(implicit session: RSession): Map[Id[NormalizedURI], Set[Id[Keep]]] = {
    if (uriIds.isEmpty) Map.empty[Id[NormalizedURI], Set[Id[Keep]]]
    else {
      import com.keepit.common.db.slick.StaticQueryFixed.interpolation
      val uriIdSet = uriIds.map(_.id).mkString("(", ",", ")")
      val q = sql"""(
        SELECT ktu.uri_id, ktu.keep_id FROM keep_to_user ktu

        WHERE ktu.state = 'active' AND ktu.user_id = $userId AND ktu.uri_id in #$uriIdSet
      ) UNION (
        SELECT ktl.uri_id, ktl.keep_id FROM keep_to_library ktl INNER JOIN library_membership lm ON ktl.library_id = lm.library_id
        WHERE ktl.state = 'active' AND ktl.uri_id in #$uriIdSet AND lm.state = 'active' AND lm.user_id = $userId #${excludeAccess.map(access => s"AND lm.access != '${access.value}'").getOrElse("")}
      );"""
      val uriAndKeepIdsByUriId = q.as[(Id[NormalizedURI], Id[Keep])].list.groupBy(_._1)
      uriIds.map { uriId => uriId -> uriAndKeepIdsByUriId.get(uriId).map(_.map(_._2).toSet).getOrElse(Set.empty) }.toMap
    }
  }

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Keep]] = Some(KeepStates.INACTIVE))(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.uriId === uriId && b.state =!= excludeState.orNull) yield b).list

  def getByUser(userId: Id[User], excludeSet: Set[State[Keep]])(implicit session: RSession): Seq[Keep] =
    (for (b <- rows if b.userId === userId && !b.state.inSet(excludeSet)) yield b).sortBy(_.keptAt).list

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

  def getBookmarksChanged(num: SequenceNumber[Keep], limit: Int)(implicit session: RSession): Seq[Keep] = super.getBySequenceNumber(num, limit)

  def exists(uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (b <- rows if b.uriId === uriId && b.state === KeepStates.ACTIVE) yield b).firstOption.isDefined
  }
  def getLatestKeepsURIByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[Id[NormalizedURI]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val sql = sql"select uri_Id from bookmark where state = '#${KeepStates.ACTIVE}' and user_id=${userId} order by kept_at DESC limit ${limit}"
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

  def pageByLibrary(libraryId: Id[Library], offset: Int, limit: Int, excludeSet: Set[State[Keep]])(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""
    SELECT #$bookmarkColumnOrder
    FROM bookmark bm INNER JOIN keep_to_library ktl ON (bm.id = ktl.keep_id)
    WHERE bm.state = 'active' AND ktl.state = 'active'
      AND ktl.library_id = $libraryId
    ORDER BY bm.last_activity_at DESC, bm.id DESC
    LIMIT $offset, $limit
    """.as[Keep].list
  }

  def getKeepIdsForQuery(query: KeepQuery)(implicit session: RSession): Seq[Id[Keep]] = {
    import KeepQuery._
    type Rows = Query[KeepTable, Keep, Seq]
    val arrangement = query.arrangement.getOrElse(Arrangement.GLOBAL_DEFAULT)
    val paging = query.paging
    val orgVisibilities: Set[LibraryVisibility] = Set(LibraryVisibility.ORGANIZATION, LibraryVisibility.PUBLISHED)

    def filterByTarget(rs: Rows): Rows = query.target match {
      case ForLibrary(targetLib) =>
        for {
          k <- rs
          ktl <- ktlRows
          if k.id === ktl.keepId &&
            ktl.libraryId === targetLib
        } yield k
      case FirstOrder(uri, viewer) =>
        for {
          k <- rs if k.uriId === uri &&
            (
              (for {
                ktu <- ktuRows if ktu.keepId === k.id && ktu.userId === viewer
              } yield ktu.keepId).exists ||
              (for {
                lm <- lmRows if lm.userId === viewer && lm.access.inSet(LibraryAccess.collaborativePermissions)
                ktl <- ktlRows if ktl.keepId === k.id && ktl.libraryId === lm.libraryId
              } yield ktl.keepId).exists
            )
        } yield k
      case ForUriAndRecipients(uri, viewer, recips) =>
        for {
          k <- rs if k.uriId === uri &&
            (if (recips.libraries.isEmpty) true else ktlRows.filter(ktl => ktl.keepId === k.id && ktl.libraryId.inSet(recips.libraries)).length === recips.libraries.size) &&
            (if (recips.users.isEmpty) true else ktuRows.filter(ktu => ktu.keepId === k.id && ktu.userId.inSet(recips.users)).length === recips.users.size) &&
            (if (recips.emails.isEmpty) true else kteRows.filter(kte => kte.keepId === k.id && kte.emailAddress.inSet(recips.emails)).length === recips.emails.size) &&
            (
              (for {
                ktu <- ktuRows if ktu.keepId === k.id && ktu.userId === viewer
              } yield ktu.keepId).exists ||
              (for {
                lm <- lmRows if lm.userId === viewer
                ktl <- ktlRows if ktl.keepId === k.id && ktl.libraryId === lm.libraryId
              } yield ktl.keepId).exists ||
              (for {
                om <- omRows if om.userId === viewer
                ktl <- ktlRows if ktl.keepId === k.id && ktl.organizationId === om.organizationId && ktl.visibility.inSet(orgVisibilities)
              } yield ktl.keepId).exists ||
              (for {
                ktl <- ktlRows if ktl.keepId === k.id && ktl.visibility === (LibraryVisibility.PUBLISHED: LibraryVisibility)
              } yield ktl.keepId).exists
            )
        } yield k
    }
    def filterSeenRows(rs: Rows): Rows = paging.filter.fold(rs) {
      case Seen(ids) => rs.filterNot(_.id.inSet(ids))
      case FromId(fromId) =>
        val fromKeep = get(fromId)
        arrangement.ordering match {
          case KeepOrdering.LAST_ACTIVITY_AT =>
            val fromTime = fromKeep.lastActivityAt
            arrangement.direction match {
              case SortDirection.ASCENDING => rs.filter(r => r.lastActivityAt > fromKeep.lastActivityAt || (r.lastActivityAt === fromTime && r.id > fromId))
              case SortDirection.DESCENDING => rs.filter(r => r.lastActivityAt < fromKeep.lastActivityAt || (r.lastActivityAt === fromTime && r.id < fromId))
            }
          case KeepOrdering.KEPT_AT =>
            val fromTime = fromKeep.keptAt
            arrangement.direction match {
              case SortDirection.ASCENDING => rs.filter(r => r.keptAt > fromKeep.keptAt || (r.keptAt === fromTime && r.id > fromId))
              case SortDirection.DESCENDING => rs.filter(r => r.keptAt < fromKeep.keptAt || (r.keptAt === fromTime && r.id < fromId))
            }
        }
    }
    def orderByTime(rs: Rows): Rows = arrangement match {
      case Arrangement(KeepOrdering.LAST_ACTIVITY_AT, SortDirection.ASCENDING) => rs.sortBy(r => (r.lastActivityAt asc, r.id asc))
      case Arrangement(KeepOrdering.LAST_ACTIVITY_AT, SortDirection.DESCENDING) => rs.sortBy(r => (r.lastActivityAt desc, r.id desc))
      case Arrangement(KeepOrdering.KEPT_AT, SortDirection.ASCENDING) => rs.sortBy(r => (r.keptAt asc, r.id asc))
      case Arrangement(KeepOrdering.KEPT_AT, SortDirection.DESCENDING) => rs.sortBy(r => (r.keptAt desc, r.id desc))
    }
    def pageThroughOrderedRows(rs: Rows) = rs.map(_.id).drop(paging.offset).take(paging.limit)

    val q = activeRows |> filterByTarget |> filterSeenRows |> orderByTime |> pageThroughOrderedRows
    q.list
  }

  def getSectionedKeepsOnUri(viewer: Id[User], uri: Id[NormalizedURI], seen: Set[Id[Keep]], limit: Int, filterRecipientOpt: Option[KeepRecipientId])(implicit session: RSession): Map[KeepProximitySection, Seq[Id[Keep]]] = {
    /*
    I am sorry that this method exists.
    It is a testament to the disasterous combination of my hubris and incompetence.

    The sections are:
      0 --> direct (i.e., keep_to_user)
      1 --> indirect via library membership
      2 --> indirect via org library
      3 --> indirect via published visibility
     */

    def keepConnectedToFilterRecipient(keepId: Column[Id[Keep]]): Column[Boolean] = {
      import KeepRecipientId._
      filterRecipientOpt match {
        case Some(UserId(uid)) => ktuRows.filter(ktu => ktu.keepId === keepId && ktu.userId === uid).exists
        case Some(LibraryId(lid)) => ktlRows.filter(ktl => ktl.keepId === keepId && ktl.libraryId === lid).exists
        case Some(email: Email) => kteRows.filter(kte => kte.keepId === keepId && kte.emailAddressHash === email.addressHash).exists
        case None => false
      }
    }

    val orgVisibilities: Set[LibraryVisibility] = Set(LibraryVisibility.ORGANIZATION, LibraryVisibility.PUBLISHED)
    val pubVisibility: LibraryVisibility = LibraryVisibility.PUBLISHED

    val direct = ktuRows.filter { ktu =>
      ktu.uriId === uri && ktu.userId === viewer && !ktu.keepId.inSet(seen) && !keepConnectedToFilterRecipient(ktu.keepId)
    }.map(ktu => (KeepProximitySection.Direct.priority, ktu.keepId, ktu.lastActivityAt)).take(limit)

    val lib = ktlRows.filter { ktl =>
      ktl.uriId === uri && ktl.libraryId.in {
        lmRows.filter(_.userId === viewer).map(_.libraryId)
      } && !ktl.keepId.inSet(seen) && !keepConnectedToFilterRecipient(ktl.keepId)
    }.map(ktl => (KeepProximitySection.LibraryMembership.priority, ktl.keepId, ktl.lastActivityAt)).take(limit)

    val org = ktlRows.filter { ktl =>
      ktl.uriId === uri && ktl.visibility.inSet(orgVisibilities) && ktl.organizationId.in {
        omRows.filter(_.userId === viewer).map(_.organizationId)
      } && !ktl.keepId.inSet(seen) && !keepConnectedToFilterRecipient(ktl.keepId)
    }.map(ktl => (KeepProximitySection.OrganizationLibrary.priority, ktl.keepId, ktl.lastActivityAt)).take(limit)

    val published = ktlRows.filter { ktl =>
      ktl.uriId === uri && ktl.visibility === pubVisibility && !ktl.keepId.inSet(seen) && !keepConnectedToFilterRecipient(ktl.keepId)
    }.map(ktl => (KeepProximitySection.PublishedLibrary.priority, ktl.keepId, ktl.lastActivityAt)).take(limit)

    (direct union lib union org union published).list.distinctBy(_._2).take(limit).groupBy(_._1).map {
      case (section, garbage) => KeepProximitySection.fromInt(section) -> garbage.map(_._2)
    }
  }

  def getByExtIdandLibraryId(extId: ExternalId[Keep], libraryId: Id[Library], excludeSet: Set[State[Keep]])(implicit session: RSession): Option[Keep] = {
    getByExtId(extId, excludeSet).filter { keep => keep.recipients.libraries.contains(libraryId) }
  }

  def getByIdGreaterThan(lowerBoundId: Id[Keep], limit: Int)(implicit session: RSession): Seq[Keep] = {
    val q = for { t <- rows if t.id > lowerBoundId } yield t
    q.sortBy(_.id asc).take(limit).list
  }

  def getKeepsByTimeWindow(uriId: Id[NormalizedURI], url: String, keptAfter: DateTime, keptBefore: DateTime)(implicit session: RSession): Set[Keep] = {
    val keeps = for { r <- rows if r.uriId === uriId && r.url === url && r.keptAt > keptAfter && r.keptAt < keptBefore } yield r
    keeps.list.toSet
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
          if (tableName == "k") s"(last_activity_at < '$before' or (last_activity_at = '$before' and k.id < $keepId))"
          else s"(last_activity_at < '$before' or (last_activity_at = '$before' and $tableName.keep_id < $keepId))"
        }
    }

    val last_activity_at_AFTER = afterIdOpt.flatMap(getKeepIdAndLastActivityAt) match {
      case None => (tableName: String) => "true"
      case Some((keepId, after)) =>
        (tableName: String) => {
          if (tableName == "k") s"(last_activity_at > '$after' or (last_activity_at = '$after' and k.id > $keepId))"
          else s"(last_activity_at > '$after' or (last_activity_at = '$after' and $tableName.keep_id > $keepId))"
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
    val keepsById = getActiveByIds(keepIds.toSet)
    keepsAndLastActivityAt.map { case (keepId, lastActivityAt) => keepsById(keepId) -> lastActivityAt }
      .filter { case (keep, _) => !shouldFilterByUser || keep.userId.contains(userId) }
  }

  def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (libIds.isEmpty) Map.empty
    else {
      val idset = libIds.map { _.id }.mkString("(", ",", ")")
      sql"""
      SELECT ktl.library_id, MAX(k.seq)
      FROM bookmark k INNER JOIN keep_to_library ktl ON (k.id = ktl.keep_id)
      WHERE k.state = 'active' AND ktl.state = 'active'
        AND ktl.library_id IN #$idset
      GROUP BY ktl.library_id
      """.as[(Id[Library], SequenceNumber[Keep])].toMap
    }
  }

  def getKeepSourcesByUser(userId: Id[User])(implicit session: RSession): Seq[KeepSource] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select distinct source from bookmark where user_id=$userId and state='active'"""
    q.as[KeepSource].list
  }

  def getChangedKeepsFromLibrary(libraryId: Id[Library], seq: SequenceNumber[Keep])(implicit session: RSession): Seq[Keep] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""
            SELECT #$bookmarkColumnOrder
            FROM bookmark bm INNER JOIN keep_to_library ktl ON (bm.id = ktl.keep_id)
            WHERE bm.state = 'active' AND ktl.state = 'active' AND ktl.library_id = $libraryId AND (bm.seq > $seq OR bm.seq < 0)
            ORDER BY bm.seq ASC
            """
    q.as[Keep].list
  }

  def pageAscendingWithUserExcludingSources(fromId: Option[Id[Keep]], size: Int, excludeStates: Set[State[Keep]], excludeSources: Set[KeepSource])(implicit session: RSession): Seq[Keep] = {
    val q = for {
      k <- rows if k.id > fromId.getOrElse(Id[Keep](0)) && k.userId.isDefined && !k.state.inSet(excludeStates) && !k.source.inSet(excludeSources)
    } yield k
    q.sortBy(_.id asc).take(size).list
  }

}
