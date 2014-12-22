package com.keepit.model

import java.sql.SQLException

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ DbSequenceAssigner, State, Id }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.timing
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.slick.jdbc.StaticQuery
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

@ImplementedBy(classOf[LibraryMembershipRepoImpl])
trait LibraryMembershipRepo extends Repo[LibraryMembership] with RepoWithDelete[LibraryMembership] with SeqNumberFunction[LibraryMembership] {
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Option[LibraryMembership]
  def getWithLibraryIdsAndUserId(libraryIds: Set[Id[Library]], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Library], LibraryMembership]
  def getWithLibraryIdAndUserIds(libraryId: Id[Library], userIds: Set[Id[User]], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[User], LibraryMembership]
  def pageWithLibraryIdAndAccess(libraryId: Id[Library], offset: Int, limit: Int, accessSet: Set[LibraryAccess],
    excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def countWithLibraryIdAndAccess(libraryId: Id[Library], access: LibraryAccess)(implicit session: RSession): Int
  def countWithLibraryIdByAccess(libraryId: Id[Library])(implicit session: RSession): Map[LibraryAccess, Int]
  def countWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Int
  def countWithAccessByLibraryId(libraryIds: Set[Id[Library]], access: LibraryAccess)(implicit session: RSession): Map[Id[Library], Int]
  def updateLastViewed(membershipId: Id[LibraryMembership])(implicit session: RWSession): Unit
  def updateLastEmailSent(membershipId: Id[LibraryMembership])(implicit session: RWSession): Unit
  def getMemberCountSinceForLibrary(libraryId: Id[Library], since: DateTime)(implicit session: RSession): Int
  def mostMembersSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)]
  def countByLibraryAccess(userId: Id[User], access: LibraryAccess)(implicit session: RSession): Int
  def countsByLibraryAccess(userId: Id[User], accesses: Set[LibraryAccess])(implicit session: RSession): Map[LibraryAccess, Int]
  def countFollowersWithOwnerId(ownerId: Id[User])(implicit session: RSession): Int
  def countLibrariesOfUserFromAnonymos(userId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int
  def countLibrariesToSelf(userId: Id[User])(implicit session: RSession): Int
  def countLibrariesForOtherUser(userId: Id[User], friendId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int
  def getOwnedLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Id[Library]]
}

@Singleton
class LibraryMembershipRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryRepo: LibraryRepo,
  val libraryMembershipCountCache: LibraryMembershipCountCache,
  val followersCountCache: FollowersCountCache,
  val memberIdCache: LibraryMembershipIdCache)
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
    def * = (id.?, libraryId, userId, access, createdAt, updatedAt, state, seq, showInSearch, listed, lastViewed, lastEmailSent) <> ((LibraryMembership.apply _).tupled, LibraryMembership.unapply)
  }

  def table(tag: Tag) = new LibraryMemberTable(tag)

  initTable()

  override def save(libraryMembership: LibraryMembership)(implicit session: RWSession): LibraryMembership = {
    val toSave = libraryMembership.copy(seq = deferredSeqNum())
    val res = super.save(toSave)
    libraryRepo.save(libraryRepo.get(libraryMembership.libraryId)) // update Library sequence number
    res
  }

  override def get(id: Id[LibraryMembership])(implicit session: RSession): LibraryMembership = {
    memberIdCache.getOrElse(LibraryMembershipIdKey(id)) {
      getCompiled(id).first
    }
  }

  def pageWithLibraryIdAndAccess(libraryId: Id[Library], offset: Int, limit: Int, accessSet: Set[LibraryAccess], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership] = {
    (for (b <- rows if b.libraryId === libraryId && b.access.inSet(accessSet) && b.state =!= excludeState.orNull) yield b).sortBy(r => (r.access, r.createdAt)).drop(offset).take(limit).list
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

  def getWithLibraryIdsAndUserId(libraryIds: Set[Id[Library]], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Library], LibraryMembership] = {
    libraryIds.size match {
      case 0 =>
        Map.empty
      case 1 =>
        getWithLibraryIdAndUserId(libraryIds.head, userId, excludeState) map { lm => (lm.libraryId -> lm) } toMap
      case _ =>
        (for (b <- rows if b.libraryId.inSet(libraryIds) && b.userId === userId && b.state =!= excludeState.orNull) yield (b.libraryId, b)).list.toMap
    }
  }

  def getWithLibraryIdAndUserIds(libraryId: Id[Library], userIds: Set[Id[User]], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[User], LibraryMembership] = {
    (for (b <- rows if b.libraryId === libraryId && b.userId.inSet(userIds) && b.state =!= excludeState.orNull) yield (b.userId, b)).list.toMap
  }

  def getMemberCountSinceForLibrary(libraryId: Id[Library], since: DateTime)(implicit session: RSession): Int = {
    Query((for (r <- rows if r.createdAt > since) yield r).length).first
  }

  def mostMembersSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)] = {
    import StaticQuery.interpolation
    sql"""select lm.library_id, count(*) as cnt from library_membership lm, library l where l.id = lm.library_id and l.state='active' and l.visibility='published' and lm.created_at > $since group by lm.library_id order by count(*) desc limit $count""".as[(Id[Library], Int)].list
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

  private val countWithLibraryIdAndAccessCompiled = Compiled { (libraryId: Column[Id[Library]], access: Column[LibraryAccess]) =>
    (for (row <- rows if row.libraryId === libraryId && row.access === access && row.state =!= LibraryMembershipStates.INACTIVE) yield row).length
  }
  def countWithLibraryIdAndAccess(libraryId: Id[Library], access: LibraryAccess)(implicit session: RSession): Int = {
    countWithLibraryIdAndAccessCompiled(libraryId, access).run
  }

  def countWithLibraryIdByAccess(libraryId: Id[Library])(implicit session: RSession): Map[LibraryAccess, Int] = {
    import StaticQuery.interpolation
    val existingAccessMap = sql"""select access, count(*) from library_membership where library_id=$libraryId and state='active' group by access""".as[(String, Int)].list
      .map(t => (LibraryAccess(t._1), t._2))
      .toMap
    LibraryAccess.getAll.map(access => (access -> existingAccessMap.getOrElse(access, 0))).toMap
  }

  def countWithAccessByLibraryId(libraryIds: Set[Id[Library]], access: LibraryAccess)(implicit session: RSession): Map[Id[Library], Int] = {
    import StaticQuery.interpolation
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
        val res = timing(s"countWithAccessByLibraryId(sz=${libraryIds.size};ids=$libraryIds)") { stmt.execute() }
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
    libMem.id.map { id =>
      memberIdCache.remove(LibraryMembershipIdKey(id))
      libraryMembershipCountCache.remove(LibraryMembershipCountKey(libMem.userId, libMem.access))
      // ugly! but the library is in an in memory cache so the cost is low
      followersCountCache.remove(FollowersCountKey(libraryRepo.get(libMem.libraryId).ownerId))
    }
  }

  override def invalidateCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    libMem.id.map { id =>
      if (libMem.state == LibraryMembershipStates.INACTIVE) {
        deleteCache(libMem)
      } else {
        memberIdCache.set(LibraryMembershipIdKey(id), libMem)
        libraryMembershipCountCache.remove(LibraryMembershipCountKey(libMem.userId, libMem.access))
        // ugly! but the library is in an in memory cache so the cost is low
        followersCountCache.remove(FollowersCountKey(libraryRepo.get(libMem.libraryId).ownerId))
      }
    }
  }

  def countByLibraryAccess(userId: Id[User], access: LibraryAccess)(implicit session: RSession): Int = {
    libraryMembershipCountCache.getOrElse(LibraryMembershipCountKey(userId, access)) {
      StaticQuery.queryNA[Int](s"select count(*) from library_membership where user_id = $userId and access = '${access.value}' ").firstOption.getOrElse(0)
    }
  }

  def countsByLibraryAccess(userId: Id[User], accesses: Set[LibraryAccess])(implicit session: RSession): Map[LibraryAccess, Int] = {
    libraryMembershipCountCache.bulkGetOrElse(accesses.map(LibraryMembershipCountKey(userId, _))) { keys =>
      import StaticQuery.interpolation
      val counts = sql"select access, count(*) from library_membership where user_id = $userId group by access".as[(String, Int)].list().toMap

      keys.toSeq.map(k => k -> counts.getOrElse(k.access.value, 0)).toMap
    }.map { case (k, v) => k.access -> v }
  }

  def countFollowersWithOwnerId(ownerId: Id[User])(implicit session: RSession): Int = {
    followersCountCache.getOrElse(FollowersCountKey(ownerId)) {
      import StaticQuery.interpolation
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $ownerId and lib.state = 'active' and lm.state = 'active'".as[Int].firstOption.getOrElse(0)
    }
  }

  //non user: number of public libraries that are “displayable on profile” (see library pref) plus libraries i follow that are public unless I oped in the "don't show libraries I follow" pref
  def countLibrariesOfUserFromAnonymos(userId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    val query = if (countFollowLibraries) {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published'"
    } else {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published' and lm.access='owner'"
    }
    query.as[Int].firstOption.getOrElse(0)
  }

  //my own profile view: total number of libraries I own and I follow, including main and secret, not including pending invites to libs
  def countLibrariesToSelf(userId: Id[User])(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    val query = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active'"
    query.as[Int].firstOption.getOrElse(0)
  }

  //logged in user viewing another’s profile: Everything in countLibrariesOfUserFromAnonymos + libraries user has access to (even if private)
  def countLibrariesForOtherUser(userId: Id[User], friendId: Id[User], countFollowLibraries: Boolean)(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $userId and lm.user_id = $friendId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query = if (countFollowLibraries) {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and ((lm.listed and lib.visibility = 'published') #$libVisibility)"
    } else {
      sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and ((lm.listed and lib.visibility = 'published' and lm.access='owner') #$libVisibility)"
    }
    query.as[Int].firstOption.getOrElse(0)
  }

  //logged in user viewing another’s profile: Everything in countLibrariesOfUserFromAnonymos + libraries user has access to (even if private)
  def getOwnedLibrariesForOtherUser(userId: Id[User], friendId: Id[User], page: Paginator)(implicit session: RSession): Seq[Id[Library]] = {
    import StaticQuery.interpolation
    val libsFriendFollow = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lib.owner_id = $userId and lm.user_id = $friendId and lib.state = 'active' and lm.state = 'active'".as[Id[Library]].list
    val libVisibility = libsFriendFollow.size match {
      case 0 => ""
      case 1 => s"or (lib.id = ${libsFriendFollow.head})"
      case _ => s"or (lib.id in (${libsFriendFollow mkString ","}))"
    }
    val query = sql"select lib.id from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = $userId and lib.state = 'active' and lm.state = 'active' and ((lm.listed and lib.visibility = 'published' and lm.access='owner') #$libVisibility) order by lib.id limit (${page.itemsToDrop}, ${page.size})"
    query.as[Id[Library]].list
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
