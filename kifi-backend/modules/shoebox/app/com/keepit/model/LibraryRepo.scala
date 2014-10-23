package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton, Provider }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ DbSequenceAssigner, Id, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.slick.jdbc.StaticQuery
import scala.slick.jdbc.StaticQuery.interpolation

@ImplementedBy(classOf[LibraryRepoImpl])
trait LibraryRepo extends Repo[Library] with SeqNumberFunction[Library] {
  def getByNameAndUserId(userId: Id[User], name: String, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getByUser(userId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE), excludeAccess: Option[LibraryAccess] = None)(implicit session: RSession): Seq[(LibraryMembership, Library)]
  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): List[Library]
  def getBySlugAndUserId(userId: Id[User], slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getByNameOrSlug(userId: Id[User], name: String, slug: LibrarySlug, excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Option[Library]
  def getOpt(ownerId: Id[User], slug: LibrarySlug)(implicit session: RSession): Option[Library]
  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession): Unit
  def getLibraries(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Library]
}

@Singleton
class LibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryMembershipRepo: Provider[LibraryMembershipRepoImpl],
  val idCache: LibraryIdCache)
    extends DbRepo[Library] with LibraryRepo with SeqNumberDbFunction[Library] with Logging {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[Library]("library_sequence")

  type RepoImpl = LibraryTable
  class LibraryTable(tag: Tag) extends RepoTable[Library](db, tag, "library") with SeqNumberColumn[Library] {
    def name = column[String]("name", O.NotNull)
    def ownerId = column[Id[User]]("owner_id", O.Nullable)
    def visibility = column[LibraryVisibility]("visibility", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def slug = column[LibrarySlug]("slug", O.NotNull)
    def kind = column[LibraryKind]("kind", O.NotNull)
    def universalLink = column[String]("universal_link", O.NotNull)
    def memberCount = column[Int]("member_count", O.NotNull)
    def lastKept = column[Option[DateTime]]("last_kept", O.Nullable)

    def * = (id.?, createdAt, updatedAt, name, ownerId, visibility, description, slug, state, seq, kind, universalLink, memberCount, lastKept) <> ((Library.apply _).tupled, Library.unapply)
  }

  def table(tag: Tag) = new LibraryTable(tag)
  initTable()

  override def save(library: Library)(implicit session: RWSession): Library = {
    val toSave = library.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  override def get(id: Id[Library])(implicit session: RSession): Library = {
    idCache.getOrElse(LibraryIdKey(id)) {
      getCompiled(id).first
    }
  }

  override def deleteCache(library: Library)(implicit session: RSession): Unit = {
    library.id.map { id =>
      idCache.remove(LibraryIdKey(id))
    }
  }

  override def invalidateCache(library: Library)(implicit session: RSession): Unit = {
    library.id.map { id =>
      if (library.state == LibraryStates.INACTIVE) {
        deleteCache(library)
      } else {
        idCache.set(LibraryIdKey(id), library)
      }
    }
  }

  private def getByNameAndUserCompiled(userId: Column[Id[User]], name: Column[String], excludeState: Option[State[Library]]) =
    Compiled { (for (b <- rows if b.name === name && b.ownerId === userId && b.state =!= excludeState.orNull) yield b) }
  def getByNameAndUserId(userId: Id[User], name: String, excludeState: Option[State[Library]])(implicit session: RSession): Option[Library] = {
    getByNameAndUserCompiled(userId, name, excludeState).firstOption
  }

  private def getBySlugAndUserCompiled(userId: Column[Id[User]], slug: Column[LibrarySlug], excludeState: Option[State[Library]]) =
    Compiled { (for (b <- rows if b.slug === slug && b.ownerId === userId && b.state =!= excludeState.orNull) yield b) }
  def getBySlugAndUserId(userId: Id[User], slug: LibrarySlug, excludeState: Option[State[Library]])(implicit session: RSession): Option[Library] = {
    getBySlugAndUserCompiled(userId, slug, excludeState).firstOption
  }

  private def getByNameOrSlugCompiled(userId: Column[Id[User]], name: Column[String], slug: Column[LibrarySlug], excludeState: Option[State[Library]]) =
    Compiled { (for (b <- rows if (b.name === name || b.slug === slug) && b.ownerId === userId && b.state =!= excludeState.orNull) yield b) }
  def getByNameOrSlug(userId: Id[User], name: String, slug: LibrarySlug, excludeState: Option[State[Library]])(implicit session: RSession): Option[Library] = {
    getByNameOrSlugCompiled(userId, name, slug, excludeState).firstOption
  }

  def getByUser(userId: Id[User], excludeState: Option[State[Library]], excludeAccess: Option[LibraryAccess])(implicit session: RSession): Seq[(LibraryMembership, Library)] = {
    val q = for {
      lib <- rows if lib.state =!= excludeState.orNull
      lm <- libraryMembershipRepo.get.rows if lm.libraryId === lib.id && lm.userId === userId && lm.access =!= excludeAccess.orNull && lm.state === LibraryMembershipStates.ACTIVE
    } yield (lm, lib)
    q.list
  }

  def getAllByOwner(ownerId: Id[User], excludeState: Option[State[Library]])(implicit session: RSession): List[Library] = {
    (for { t <- rows if t.ownerId === ownerId && t.state =!= excludeState.orNull } yield t).list()
  }

  def updateLastKept(libraryId: Id[Library])(implicit session: RWSession) = {
    val updateTime = Some(clock.now)
    (for { t <- rows if t.id === libraryId } yield (t.lastKept)).update(updateTime)
    invalidateCache(get(libraryId).copy(lastKept = updateTime))
  }

  private def getOptCompiled(ownerId: Column[Id[User]], slug: Column[LibrarySlug]) = Compiled {
    (for (r <- rows if r.ownerId === ownerId && r.slug === slug) yield r)
  }
  def getOpt(ownerId: Id[User], slug: LibrarySlug)(implicit session: RSession): Option[Library] = {
    getOptCompiled(ownerId, slug).firstOption
  }

  override def assignSequenceNumbers(limit: Int = 20)(implicit session: RWSession): Int = {
    assignSequenceNumbers(sequence, "library", limit)
  }

  override def minDeferredSequenceNumber()(implicit session: RSession): Option[Long] = {
    import StaticQuery.interpolation
    sql"""select min(seq) from library where seq < 0""".as[Option[Long]].first
  }

  def getLibraries(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Library] = {
    idCache.bulkGetOrElse(libraryIds.map(LibraryIdKey(_))) { missingKeys =>
      getLibrariesCompiled(missingKeys.map(_.id)).list.map(library => LibraryIdKey(library.id.get) -> library).toMap
    }.map { case (libraryKey, library) => libraryKey.id -> library }
  }

  private def getLibrariesCompiled(libraryIds: Set[Id[Library]]) = Compiled {
    (for (r <- rows if r.id.inSet(libraryIds)) yield r)
  }

}

trait LibrarySequencingPlugin extends SequencingPlugin

class LibrarySequencingPluginImpl @Inject() (
    override val actor: ActorInstance[LibrarySequencingActor],
    override val scheduling: SchedulingProperties) extends LibrarySequencingPlugin {

  override val interval: FiniteDuration = 20.seconds
}

@Singleton
class LibrarySequenceNumberAssigner @Inject() (db: Database, repo: LibraryRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[Library](db, repo, airbrake)

class LibrarySequencingActor @Inject() (
  assigner: LibrarySequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
