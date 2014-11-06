package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ DbSequenceAssigner, State, Id }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.concurrent.duration._
import scala.slick.jdbc.StaticQuery
import scala.collection.immutable.Seq

import scala.concurrent.duration.FiniteDuration

@ImplementedBy(classOf[LibraryMembershipRepoImpl])
trait LibraryMembershipRepo extends Repo[LibraryMembership] with RepoWithDelete[LibraryMembership] with SeqNumberFunction[LibraryMembership] {
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Option[LibraryMembership]
  def getWithLibraryIdAndUserIds(libraryId: Id[Library], userIds: Set[Id[User]], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[User], LibraryMembership]
  def pageWithLibraryIdAndAccess(libraryId: Id[Library], offset: Int, limit: Int, accessSet: Set[LibraryAccess],
    excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def countWithLibraryIdByAccess(libraryId: Id[Library])(implicit session: RSession): Map[LibraryAccess, Int]
  def countWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Int
  def updateLastViewed(membershipId: Id[LibraryMembership])(implicit session: RWSession): Unit
  def updateLastEmailSent(membershipId: Id[LibraryMembership])(implicit session: RWSession): Unit
  def getMemberCountSinceForLibrary(libraryId: Id[Library], since: DateTime)(implicit session: RSession): Int
  def mostMembersSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)]
  def countByLibraryAccess(userId: Id[User], access: LibraryAccess)(implicit session: RSession): Int
}

@Singleton
class LibraryMembershipRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryRepo: LibraryRepo,
  val libraryMembershipCountCache: LibraryMembershipCountCache,
  val memberIdCache: LibraryMembershipIdCache)
    extends DbRepo[LibraryMembership] with DbRepoWithDelete[LibraryMembership] with LibraryMembershipRepo with SeqNumberDbFunction[LibraryMembership] with Logging {

  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[LibraryMembership]("library_membership_sequence")

  type RepoImpl = LibraryMemberTable

  class LibraryMemberTable(tag: Tag) extends RepoTable[LibraryMembership](db, tag, "library_membership") with SeqNumberColumn[LibraryMembership] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def access = column[LibraryAccess]("access", O.NotNull)
    def showInSearch = column[Boolean]("show_in_search", O.NotNull)
    def lastViewed = column[Option[DateTime]]("last_viewed", O.Nullable)
    def lastEmailSent = column[Option[DateTime]]("last_email_sent", O.Nullable)
    def * = (id.?, libraryId, userId, access, createdAt, updatedAt, state, seq, showInSearch, lastViewed, lastEmailSent) <> ((LibraryMembership.apply _).tupled, LibraryMembership.unapply)
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

  def countWithLibraryIdByAccess(libraryId: Id[Library])(implicit session: RSession): Map[LibraryAccess, Int] = {
    import StaticQuery.interpolation
    val existingAccessMap = sql"""select access, count(*) from library_membership where library_id=$libraryId and state='active' group by access""".as[(String, Int)].list
      .map(t => (LibraryAccess(t._1), t._2))
      .toMap
    LibraryAccess.getAll.map(access => (access -> existingAccessMap.getOrElse(access, 0))).toMap
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
    }
  }

  override def invalidateCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    libMem.id.map { id =>
      if (libMem.state == LibraryMembershipStates.INACTIVE) {
        deleteCache(libMem)
      } else {
        memberIdCache.set(LibraryMembershipIdKey(id), libMem)
        libraryMembershipCountCache.remove(LibraryMembershipCountKey(libMem.userId, libMem.access))
      }
    }
  }

  override def assignSequenceNumbers(limit: Int = 20)(implicit session: RWSession): Int = {
    assignSequenceNumbers(sequence, "library_membership", limit)
  }

  override def minDeferredSequenceNumber()(implicit session: RSession): Option[Long] = {
    import StaticQuery.interpolation
    sql"""select min(seq) from library_membership where seq < 0""".as[Option[Long]].first
  }

  def countByLibraryAccess(userId: Id[User], access: LibraryAccess)(implicit session: RSession): Int = {
    libraryMembershipCountCache.getOrElse(LibraryMembershipCountKey(userId, access)) {
      StaticQuery.queryNA[Int](s"select count(user_id) from library_membership where user_id = $userId and access = '${access.value}' group by user_id").firstOption.getOrElse(0)
    }
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
