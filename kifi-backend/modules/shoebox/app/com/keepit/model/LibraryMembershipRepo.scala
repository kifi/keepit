package com.keepit.model

import java.sql.SQLException

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.commanders.{ RelevantSuggestedLibrariesKey, RelevantSuggestedLibrariesCache }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ DbSequenceAssigner, State, Id }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ timing, StatsdTiming }
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time._
import com.keepit.typeahead.{ LibraryFilterTypeaheadKey, LibraryFilterTypeaheadCache }
import org.joda.time.DateTime
import play.api.libs.json.Json
import scala.concurrent.duration._
import scala.slick.jdbc.StaticQuery
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

// a tuple with named fields, for when many rows need to be read and only a few fields are needed
case class MiniLibraryMembership(userId: Id[User], access: LibraryAccess, lastViewed: Option[DateTime], listed: Boolean, subscribed: Boolean)

@ImplementedBy(classOf[LibraryMembershipRepoImpl])
trait LibraryMembershipRepo extends Repo[LibraryMembership] with RepoWithDelete[LibraryMembership] with SeqNumberFunction[LibraryMembership] {
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getLibrariesWithWriteAccess(userId: Id[User])(implicit session: RSession): Set[Id[Library]]
  def getLatestUpdatedLibraryUserFollow(userId: Id[User])(implicit session: RSession): Option[Library]
  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Option[LibraryMembership]
  def getWithLibraryIdsAndUserId(libraryIds: Set[Id[Library]], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Library], LibraryMembership]
  def getWithLibraryIdAndUserIds(libraryId: Id[Library], userIds: Set[Id[User]], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[User], LibraryMembership]
  def pageWithLibraryIdAndAccess(libraryId: Id[Library], offset: Int, limit: Int, accessSet: Set[LibraryAccess],
    excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def pageByUserId(userId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[LibraryMembership]
  def someWithLibraryIdAndAccess(libraryId: Id[Library], limit: Int, access: LibraryAccess, excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getMinisByLibraryIdsAndAccess(libraryIds: Set[Id[Library]], accessSet: Set[LibraryAccess])(implicit session: RSession): Map[Id[Library], Seq[MiniLibraryMembership]]
  def countWithLibraryIdAndAccess(libraryId: Id[Library], access: LibraryAccess)(implicit session: RSession): Int
  def countWithLibraryIdByAccess(libraryId: Id[Library])(implicit session: RSession): CountWithLibraryIdByAccess
  def countWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Int
  def countByLibraryId(libraryIds: Set[Id[Library]], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Int]
  def countWithAccessByLibraryId(libraryIds: Set[Id[Library]], access: LibraryAccess)(implicit session: RSession): Map[Id[Library], Int]
  def updateLastViewed(membershipId: Id[LibraryMembership])(implicit session: RWSession): Unit
  def updateLastEmailSent(membershipId: Id[LibraryMembership])(implicit session: RWSession): Unit
  def countMembersForLibrarySince(libraryId: Id[Library], since: DateTime)(implicit session: RSession): Int
  def mostMembersSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)]
  def percentGainSince(since: DateTime, totalMoreThan: Int, recentMoreThan: Int, count: Int)(implicit session: RSession): Seq[(Id[Library], Int, Int, Double)]
  def mostMembersSinceForUser(count: Int, since: DateTime, ownerId: Id[User])(implicit session: RSession): Seq[(Id[Library], Int)]
  def countNonTrivialLibrariesWithUserIdAndAccess(userId: Id[User], access: LibraryAccess, minKeepCount: Int = 1)(implicit session: RSession): Int
  def countsWithUserIdAndAccesses(userId: Id[User], accesses: Set[LibraryAccess])(implicit session: RSession): Map[LibraryAccess, Int]
  def getCollaboratorsByLibrary(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Set[Id[User]]]
  def getCollaborators(userId: Id[User])(implicit session: RSession): Set[Id[User]]

  //
  // Profile Library Repo functions
  //
  def getFollowersForAnonymous(ownerId: Id[User])(implicit session: RSession): Seq[Id[User]]
  def getFollowersForOwner(ownerId: Id[User])(implicit session: RSession): Seq[Id[User]]
  def getFollowersForOtherUser(ownerId: Id[User], viewerId: Id[User])(implicit session: RSession): Seq[Id[User]]
  def countFollowersForAnonymous(userId: Id[User])(implicit session: RSession): Int
  def countFollowersForOwner(ownerId: Id[User])(implicit session: RSession): Int
  def countFollowersForOtherUser(ownerId: Id[User], viewerId: Id[User])(implicit session: RSession): Int
  def userRecentUniqueFollowerCounts(ownerId: Id[User], since: DateTime)(implicit session: RSession): Int
  def userRecentTopFollowedLibrariesAndCounts(ownerId: Id[User], since: DateTime, limit: Int = 7)(implicit session: RSession): Map[Id[Library], Int]
}

@Singleton
class LibraryMembershipRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  libraryRepo: LibraryRepoImpl,
  libraryMembershipCountCache: LibraryMembershipCountCache,
  relevantSuggestedLibrariesCache: RelevantSuggestedLibrariesCache,
  libraryFilterTypeaheadCache: LibraryFilterTypeaheadCache,
  followersCountCache: FollowersCountCache,
  countWithLibraryIdByAccessCache: CountWithLibraryIdByAccessCache,
  librariesWithWriteAccessCache: LibrariesWithWriteAccessCache,
  countByLibIdAndAccessCache: LibraryMembershipCountByLibIdAndAccessCache)
    extends DbRepo[LibraryMembership] with DbRepoWithDelete[LibraryMembership] with LibraryMembershipRepo with SeqNumberDbFunction[LibraryMembership] with Logging {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = LibraryMemberTable

  class LibraryMemberTable(tag: Tag) extends RepoTable[LibraryMembership](db, tag, "library_membership") with SeqNumberColumn[LibraryMembership] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def access = column[LibraryAccess]("access", O.NotNull)
    def showInSearch = column[Boolean]("show_in_search", O.NotNull)
    def lastViewed = column[Option[DateTime]]("last_viewed", O.Nullable)
    def lastEmailSent = column[Option[DateTime]]("last_email_sent", O.Nullable)
    def listed = column[Boolean]("listed", O.NotNull)
    def subscribedToUpdates = column[Boolean]("subscribed_to_updates", O.NotNull)
    def priority = column[Long]("priority", O.NotNull)
    def * = (id.?, libraryId, userId, access, createdAt, updatedAt, state, seq, showInSearch, listed, lastViewed, lastEmailSent, subscribedToUpdates, priority) <> ((LibraryMembership.apply _).tupled, LibraryMembership.unapply)
  }

  implicit val getLibraryResult = libraryRepo.getLibraryResult

  def table(tag: Tag) = new LibraryMemberTable(tag)
  initTable()

  def activeRows = rows.filter(_.state === LibraryMembershipStates.ACTIVE)

  override def save(libraryMembership: LibraryMembership)(implicit session: RWSession): LibraryMembership = {
    val toSave = libraryMembership.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  @StatsdTiming("LibraryMembershipRepo.pageWithLibraryIdAndAccess")
  def pageWithLibraryIdAndAccess(libraryId: Id[Library], offset: Int, limit: Int, accessSet: Set[LibraryAccess], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership] = {
    val safeOffset = if (offset >= 0) offset else 0
    // This can be removed once clients stop calling with offset = -1
    (for (b <- rows if b.libraryId === libraryId && b.access.inSet(accessSet) && b.state =!= excludeState.orNull) yield b).sortBy(r => (r.access, r.id)).drop(safeOffset).take(limit).list
  }

  def pageByUserId(userId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[LibraryMembership] = {
    activeRows.filter(_.userId === userId).sortBy(_.id).drop(offset max 0).take(limit).list
  }

  @StatsdTiming("LibraryMembershipRepo.someWithLibraryIdAndAccess")
  def someWithLibraryIdAndAccess(libraryId: Id[Library], limit: Int, access: LibraryAccess, excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership] = {
    (for (b <- rows if b.libraryId === libraryId && b.access === access && b.state =!= excludeState.orNull) yield b).take(limit).list
  }

  private val getWithLibraryIdCompiled = Compiled { (libraryId: Column[Id[Library]]) =>
    (for (row <- rows if row.libraryId === libraryId) yield row).sortBy(_.createdAt)
  }
  private val getWithLibraryIdWithExcludeCompiled = Compiled { (libraryId: Column[Id[Library]], excludeState: Column[State[LibraryMembership]]) =>
    (for (row <- rows if row.libraryId === libraryId && row.state =!= excludeState) yield row).sortBy(_.createdAt)
  }
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership] = {
    excludeState match {
      case None => getWithLibraryIdCompiled(libraryId).list
      case Some(exclude) => getWithLibraryIdWithExcludeCompiled(libraryId, exclude).list
    }
  }

  private val getWithUserIdCompiled = Compiled { (userId: Column[Id[User]]) =>
    (for (row <- rows if row.userId === userId) yield row).sortBy(_.createdAt)
  }
  private val getWithUserIdWithExcludeCompiled = Compiled { (userId: Column[Id[User]], excludeState: Column[State[LibraryMembership]]) =>
    (for (row <- rows if row.userId === userId && row.state =!= excludeState) yield row).sortBy(_.createdAt)
  }
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership] = {
    excludeState match {
      case None => getWithUserIdCompiled(userId).list
      case Some(exclude) => getWithUserIdWithExcludeCompiled(userId, exclude).list
    }
  }

  def getLibrariesWithWriteAccess(userId: Id[User])(implicit session: RSession): Set[Id[Library]] = {
    librariesWithWriteAccessCache.getOrElse(LibrariesWithWriteAccessUserKey(userId)) {
      getWithUserId(userId, Some(LibraryMembershipStates.INACTIVE)).collect { case membership if membership.canWrite => membership.libraryId }.toSet
    }
  }

  def getLatestUpdatedLibraryUserFollow(userId: Id[User])(implicit session: RSession): Option[Library] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select l.* from library_membership lm, library l where l.id = lm.library_id and lm.state='active' and l.state='active' and lm.access != 'owner' and lm.user_id = $userId and l.last_kept is not null and l.keep_count > 0 order by l.last_kept desc limit 1""".as[Library].firstOption
  }

  private val getWithLibraryIdAndUserIdCompiled = Compiled { (libraryId: Column[Id[Library]], userId: Column[Id[User]]) =>
    for (row <- rows if row.libraryId === libraryId && row.userId === userId) yield row
  }
  private val getWithLibraryIdAndUserIdWithExcludeCompiled = Compiled { (libraryId: Column[Id[Library]], userId: Column[Id[User]], excludeState: Column[State[LibraryMembership]]) =>
    for (row <- rows if row.libraryId === libraryId && row.userId === userId && row.state =!= excludeState) yield row
  }
  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Option[LibraryMembership] = {
    excludeState match {
      case None => getWithLibraryIdAndUserIdCompiled(libraryId, userId).firstOption
      case Some(exclude) => getWithLibraryIdAndUserIdWithExcludeCompiled(libraryId, userId, exclude).firstOption
    }
  }

  @StatsdTiming("LibraryMembershipRepo.getWithLibraryIdsAndUserId")
  def getWithLibraryIdsAndUserId(libraryIds: Set[Id[Library]], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Library], LibraryMembership] = {
    if (libraryIds.isEmpty) Map.empty[Id[Library], LibraryMembership]
    else (for (b <- rows if b.libraryId.inSet(libraryIds) && b.userId === userId && b.state =!= excludeState.orNull) yield (b.libraryId, b)).list.toMap
  }

  def getWithLibraryIdAndUserIds(libraryId: Id[Library], userIds: Set[Id[User]], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[User], LibraryMembership] = {
    (for (b <- rows if b.libraryId === libraryId && b.userId.inSet(userIds) && b.state =!= excludeState.orNull) yield (b.userId, b)).list.toMap
  }

  def getMinisByLibraryIdsAndAccess(libraryIds: Set[Id[Library]], accessSet: Set[LibraryAccess])(implicit session: RSession): Map[Id[Library], Seq[MiniLibraryMembership]] = {
    (for (b <- rows if b.libraryId.inSet(libraryIds) && b.access.inSet(accessSet) && b.state === LibraryMembershipStates.ACTIVE) yield (b.libraryId, b.userId, b.access, b.lastViewed, b.listed, b.subscribedToUpdates, b.createdAt))
      .sortBy(r => (r._1, r._3.desc, r._5)) // libraryId, access desc (owner first), createdAt
      .list
      .groupBy(_._1)
      .mapValues(_.map(t => MiniLibraryMembership(t._2, t._3, t._4, t._5, t._6)))
  }

  def countMembersForLibrarySince(libraryId: Id[Library], since: DateTime)(implicit session: RSession): Int = {
    Query((for (r <- rows if r.createdAt > since && r.libraryId === libraryId) yield r).length).first
  }

  def mostMembersSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select lm.library_id, count(*) as cnt from library_membership lm, library l where l.id = lm.library_id and l.state='active' and l.visibility='published' and lm.created_at > $since group by lm.library_id order by count(*) desc limit $count""".as[(Id[Library], Int)].list
  }

  def percentGainSince(since: DateTime, totalMoreThan: Int, recentMoreThan: Int, count: Int)(implicit session: RSession): Seq[(Id[Library], Int, Int, Double)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""
         SELECT tp.library_id,
                tp.new_followers,
                count(lm2.id) AS total_followers,
                new_followers/count(lm2.id) AS growth
         FROM
           (SELECT lm.library_id,
                   count(*) AS new_followers
            FROM library_membership lm,
                 library l
            WHERE l.id = lm.library_id
              AND l.state='active'
              AND l.visibility='published'
              AND lm.created_at > $since
            GROUP BY lm.library_id
            HAVING new_followers > $recentMoreThan
            ORDER BY new_followers DESC) tp, library_membership lm2
         WHERE lm2.library_id = tp.library_id
         GROUP BY tp.library_id
         HAVING total_followers > $totalMoreThan
         ORDER BY growth DESC
         LIMIT $count
       """.as[(Id[Library], Int, Int, Double)].list
  }

  def mostMembersSinceForUser(count: Int, since: DateTime, ownerId: Id[User])(implicit session: RSession): Seq[(Id[Library], Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select lm.library_id, count(*) as cnt from library_membership lm, library l where l.owner_id=$ownerId and l.id = lm.library_id and l.state='active' and l.visibility='published' and lm.created_at > $since group by lm.library_id order by count(*) desc limit $count""".as[(Id[Library], Int)].list
  }

  private val countMembershipsCompiled = Compiled { (libraryId: Column[Id[Library]]) =>
    (for (row <- rows if row.libraryId === libraryId) yield row).length
  }
  private val countMembershipsWithExcludeCompiled = Compiled { (libraryId: Column[Id[Library]], excludeState: Column[State[LibraryMembership]]) =>
    (for (row <- rows if row.libraryId === libraryId && row.state =!= excludeState) yield row).length
  }
  def countWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Int = {
    excludeState match {
      case None => countMembershipsCompiled(libraryId).run
      case Some(exclude) => countMembershipsWithExcludeCompiled(libraryId, exclude).run
    }
  }

  def countByLibraryId(libraryIds: Set[Id[Library]], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Int] = {
    (for (row <- rows if row.libraryId.inSet(libraryIds) && row.state =!= excludeState.orNull) yield row).groupBy(_.libraryId).map { case (libraryId, results) => libraryId -> results.length }.toMap
  }

  private val countWithLibraryIdAndAccessCompiled = Compiled { (libraryId: Column[Id[Library]], access: Column[LibraryAccess]) =>
    (for (row <- rows if row.libraryId === libraryId && row.access === access && row.state =!= LibraryMembershipStates.INACTIVE) yield row.id).length
  }
  def countWithLibraryIdAndAccess(libraryId: Id[Library], access: LibraryAccess)(implicit session: RSession): Int = {
    countByLibIdAndAccessCache.getOrElse(LibraryMembershipCountByLibIdAndAccessKey(libraryId, access)) {
      countWithLibraryIdAndAccessCompiled(libraryId, access).run
    }
  }

  @StatsdTiming("LibraryMembershipRepo.countWithLibraryIdByAccess")
  def countWithLibraryIdByAccess(libraryId: Id[Library])(implicit session: RSession): CountWithLibraryIdByAccess = {
    countWithLibraryIdByAccessCache.getOrElse(CountWithLibraryIdByAccessKey(libraryId)) {
      import com.keepit.common.db.slick.StaticQueryFixed.interpolation
      val existingAccessMap = sql"""select access, count(*) from library_membership where library_id=$libraryId and state='active' group by access""".as[(String, Int)].list
        .map(t => (LibraryAccess(t._1), t._2))
        .toMap
      val counts: Map[LibraryAccess, Int] = LibraryAccess.all.map(access => access -> existingAccessMap.getOrElse(access, 0)).toMap
      CountWithLibraryIdByAccess.fromMap(counts)
    }
  }

  def countWithAccessByLibraryId(libraryIds: Set[Id[Library]], access: LibraryAccess)(implicit session: RSession): Map[Id[Library], Int] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    libraryIds.size match {
      case 0 =>
        Map.empty
      case 1 =>
        val libraryId = libraryIds.head
        Map(libraryId -> countWithLibraryIdAndAccess(libraryId, access))
      case n =>
        val ids = Seq.fill(n)("?").mkString(",")
        val stmt = session.getPreparedStatement(s"select library_id, count(*) from library_membership where library_id in ($ids) and access='${access.value}' and state='active' group by library_id")
        libraryIds.zipWithIndex.foreach {
          case (id, idx) =>
            stmt.setLong(idx + 1, id.id)
        }
        val res = timing(s"countWithAccessByLibraryId(sz=${libraryIds.size};ids=${libraryIds.take(10)})") { stmt.execute() }
        if (!res) throw new SQLException(s"[countWithAccessByLibraryId] ($stmt) failed to execute")
        val rs = stmt.getResultSet()
        val buf = collection.mutable.ArrayBuilder.make[(Id[Library], Int)]
        while (rs.next()) {
          val libraryId = Id[Library](rs.getLong(1))
          val count = rs.getInt(2)
          buf += (libraryId -> count)
        }
        val counts = buf.result.toMap
        libraryIds.map(id => (id, counts.getOrElse(id, 0))).toMap
    }
  }

  def updateLastViewed(membershipId: Id[LibraryMembership])(implicit session: RWSession) = {
    val updateTime = Some(clock.now)
    (for { t <- rows if t.id === membershipId } yield (t.lastViewed)).update(updateTime)
    invalidateCache(get(membershipId).copy(lastViewed = updateTime))
  }

  def updateLastEmailSent(membershipId: Id[LibraryMembership])(implicit session: RWSession) = {
    val updateTime = Some(clock.now)
    (for { t <- rows if t.id === membershipId } yield (t.lastEmailSent)).update(updateTime)
    invalidateCache(get(membershipId).copy(lastEmailSent = updateTime))
  }

  override def deleteCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    relevantSuggestedLibrariesCache.remove(RelevantSuggestedLibrariesKey(libMem.userId))
    libraryFilterTypeaheadCache.remove(LibraryFilterTypeaheadKey(libMem.userId))
    countWithLibraryIdByAccessCache.remove(CountWithLibraryIdByAccessKey(libMem.libraryId))
    countByLibIdAndAccessCache.remove(LibraryMembershipCountByLibIdAndAccessKey(libMem.libraryId, libMem.access))
    libraryMembershipCountCache.remove(LibraryMembershipCountKey(libMem.userId, libMem.access))
    if (libMem.canWrite) { librariesWithWriteAccessCache.remove(LibrariesWithWriteAccessUserKey(libMem.userId)) }
    // ugly! but the library is in an in memory cache so the cost is low
    val ownerId = libraryRepo.get(libMem.libraryId).ownerId
    followersCountCache.remove(FollowersCountKey(ownerId))
  }

  override def invalidateCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    relevantSuggestedLibrariesCache.remove(RelevantSuggestedLibrariesKey(libMem.userId))
    libraryFilterTypeaheadCache.remove(LibraryFilterTypeaheadKey(libMem.userId))
    countByLibIdAndAccessCache.remove(LibraryMembershipCountByLibIdAndAccessKey(libMem.libraryId, libMem.access))
    libraryMembershipCountCache.remove(LibraryMembershipCountKey(libMem.userId, libMem.access))
    if (libMem.canWrite) { librariesWithWriteAccessCache.remove(LibrariesWithWriteAccessUserKey(libMem.userId)) }
    // ugly! but the library is in an in memory cache so the cost is low
    val ownerId = libraryRepo.get(libMem.libraryId).ownerId
    followersCountCache.remove(FollowersCountKey(ownerId))
    countWithLibraryIdByAccessCache.remove(CountWithLibraryIdByAccessKey(libMem.libraryId))
  }

  def countNonTrivialLibrariesWithUserIdAndAccess(userId: Id[User], access: LibraryAccess, minKeepCount: Int = 1)(implicit session: RSession): Int = {
    libraryMembershipCountCache.getOrElse(LibraryMembershipCountKey(userId, access)) {
      StaticQuery.queryNA[Int](
        s"select count(*) from library_membership lm, library l where " +
          s"lm.library_id = l.id and l.kind = 'user_created' and l.last_kept is not null and l.keep_count > $minKeepCount and l.state = 'active' and lm.user_id = $userId and lm.access = '${access.value}' and lm.state = 'active'")
        .firstOption.getOrElse(0)
    }
  }

  def countsWithUserIdAndAccesses(userId: Id[User], accesses: Set[LibraryAccess])(implicit session: RSession): Map[LibraryAccess, Int] = {
    libraryMembershipCountCache.bulkGetOrElse(accesses.map(LibraryMembershipCountKey(userId, _))) { keys =>
      import com.keepit.common.db.slick.StaticQueryFixed.interpolation
      val counts = sql"select access, count(*) from library_membership where user_id = $userId and state = 'active' group by access".as[(String, Int)].list.toMap

      keys.toSeq.map(k => k -> counts.getOrElse(k.access.value, 0)).toMap
    }.map { case (k, v) => k.access -> v }
  }

  def getFollowersForOwner(ownerId: Id[User])(implicit session: RSession): Seq[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select distinct lm.user_id from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.access != 'owner' and lm.state = 'active'"
    q.as[Id[User]].list
  }
  def getFollowersForAnonymous(ownerId: Id[User])(implicit session: RSession): Seq[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select distinct lm.user_id from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.access != 'owner' and lm.state = 'active' and lib.visibility = 'published'"
    q.as[Id[User]].list
  }
  def getFollowersForOtherUser(ownerId: Id[User], viewerId: Id[User])(implicit session: RSession): Seq[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select distinct lm.user_id from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.access != 'owner' and lm.state = 'active' and (lib.visibility = 'published' or (lib.visibility='secret' and lm.user_id = $viewerId))"
    q.as[Id[User]].list
  }

  def countFollowersForOwner(ownerId: Id[User])(implicit session: RSession): Int = {
    followersCountCache.getOrElse(FollowersCountKey(ownerId)) {
      import com.keepit.common.db.slick.StaticQueryFixed.interpolation
      sql"select count(distinct lm.user_id) from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.access != 'owner' and lm.state = 'active'".as[Int].firstOption.getOrElse(0)
    }
  }

  def countFollowersForAnonymous(ownerId: Id[User])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select count(distinct lm.user_id) from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.access != 'owner' and lm.state = 'active' and lib.visibility = 'published'"
    q.as[Int].firstOption.getOrElse(0)
  }

  def countFollowersForOtherUser(ownerId: Id[User], viewerId: Id[User])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select count(distinct lm.user_id) from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.access != 'owner' and lm.state = 'active' and (lib.visibility = 'published' or (lib.visibility='secret' and lm.user_id = $viewerId))"
    q.as[Int].firstOption.getOrElse(0)
  }

  def userRecentUniqueFollowerCounts(ownerId: Id[User], since: DateTime)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select count(distinct lm.user_id) from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.access != 'owner' and lm.created_at > $since and lm.state = 'active'"
    q.as[Int].firstOption.getOrElse(0)
  }

  def userRecentTopFollowedLibrariesAndCounts(ownerId: Id[User], since: DateTime, limit: Int = 5)(implicit session: RSession): Map[Id[Library], Int] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select lib.id, count(*) cnt from library as lib inner join library_membership as lm on lib.id = lm.library_id where lib.owner_id = ${ownerId} and lm.access != 'owner' and lm.created_at >= ${since} group by lib.id order by cnt desc limit $limit"
    q.as[(Int, Int)].list.map { case (id, cnt) => (Id[Library](id), cnt) }.toMap
  }

  def getCollaboratorsByLibrary(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Set[Id[User]]] = {
    val q = rows.filter(row => row.libraryId.inSet(libIds) && row.state === LibraryMembershipStates.ACTIVE && row.access.inSet(LibraryAccess.collaborativePermissions))
    val result = q.list.groupBy(_.libraryId).map { case (libId, memberships) => libId -> memberships.map(_.userId).toSet }
    libIds.map { libId => libId -> result.getOrElse(libId, Set.empty) }.toMap
  }

  private val getCollaboratorsCompiled = Compiled { (userId: Column[Id[User]]) =>
    for {
      userMembership <- rows if userMembership.userId === userId && userMembership.state === LibraryMembershipStates.ACTIVE && userMembership.access.inSet(LibraryAccess.collaborativePermissions)
      libMembership <- rows if libMembership.libraryId === userMembership.libraryId && libMembership.userId =!= userId && libMembership.state === LibraryMembershipStates.ACTIVE && libMembership.access.inSet(LibraryAccess.collaborativePermissions)
    } yield libMembership.userId
  }

  def getCollaborators(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    getCollaboratorsCompiled(userId).run.toSet
  }
}

trait LibraryMembershipSequencingPlugin extends SequencingPlugin

class LibraryMembershipSequencingPluginImpl @Inject() (
    override val actor: ActorInstance[LibraryMembershipSequencingActor],
    override val scheduling: SchedulingProperties) extends LibraryMembershipSequencingPlugin {

  override val interval: FiniteDuration = 20.seconds
}

@Singleton
class LibraryMembershipSequenceNumberAssigner @Inject() (db: Database, repo: LibraryMembershipRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[LibraryMembership](db, repo, airbrake)

class LibraryMembershipSequencingActor @Inject() (
  assigner: LibraryMembershipSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
