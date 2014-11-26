package com.keepit.curator.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.{ DbSequenceAssigner, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ SeqNumberDbFunction, Database, DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time.Clock
import com.keepit.model.{ LibraryKind, LibraryVisibility, Library, User }
import org.joda.time.DateTime

import scala.concurrent.duration._

@ImplementedBy(classOf[CuratorLibraryInfoRepoImpl])
trait CuratorLibraryInfoRepo extends DbRepo[CuratorLibraryInfo] with SeqNumberDbFunction[CuratorLibraryInfo] {
  def getByLibraryId(libraryId: Id[Library])(implicit session: RSession): Option[CuratorLibraryInfo]
}

@Singleton
class CuratorLibraryInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CuratorLibraryInfo] with CuratorLibraryInfoRepo {

  import db.Driver.simple._

  type RepoImpl = CuratorLibraryInfoTable
  class CuratorLibraryInfoTable(tag: Tag) extends RepoTable[CuratorLibraryInfo](db, tag, "curator_library_info") with SeqNumberColumn[CuratorLibraryInfo] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def memberCount = column[Int]("member_count", O.NotNull)
    def keepCount = column[Int]("keep_count", O.NotNull)
    def visibility = column[LibraryVisibility]("visibility", O.NotNull)
    def lastKept = column[DateTime]("last_kept", O.Nullable)
    def lastFollowed = column[DateTime]("last_followed", O.Nullable)
    def kind = column[LibraryKind]("kind", O.NotNull)
    def libraryLastUpdated = column[DateTime]("library_last_updated", O.NotNull)
    def * = (id.?, createdAt, updatedAt, seq, libraryId, ownerId, memberCount, keepCount, visibility, lastKept.?,
      lastFollowed.?, kind, libraryLastUpdated, state) <> ((CuratorLibraryInfo.apply _).tupled, CuratorLibraryInfo.unapply)
  }

  def table(tag: Tag) = new CuratorLibraryInfoTable(tag)
  initTable()

  override def save(curatorLibraryInfo: CuratorLibraryInfo)(implicit session: RWSession): CuratorLibraryInfo = {
    val toSave = curatorLibraryInfo.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  def deleteCache(model: CuratorLibraryInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorLibraryInfo)(implicit session: RSession): Unit = {}

  def getByLibraryId(libraryId: Id[Library])(implicit session: RSession): Option[CuratorLibraryInfo] = {
    (for (row <- rows if row.libraryId === libraryId) yield row).firstOption
  }

}

trait CuratorLibraryInfoSequencingPlugin extends SequencingPlugin

class CuratorLibraryInfoSequencingPluginImpl @Inject() (
    override val actor: ActorInstance[CuratorLibraryInfoSequencingActor],
    override val scheduling: SchedulingProperties) extends CuratorLibraryInfoSequencingPlugin {
  override val interval: FiniteDuration = 60.seconds
}

@Singleton
class CuratorLibraryInfoSequenceNumberAssigner @Inject() (db: Database, repo: CuratorLibraryInfoRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[CuratorLibraryInfo](db, repo, airbrake)

class CuratorLibraryInfoSequencingActor @Inject() (
  assigner: CuratorLibraryInfoSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
