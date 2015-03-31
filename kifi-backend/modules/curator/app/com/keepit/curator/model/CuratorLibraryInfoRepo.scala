package com.keepit.curator.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, Database, DbRepo, SeqNumberDbFunction }
import com.keepit.common.db.{ DbSequenceAssigner, H2DatabaseDialect, Id, SequenceNumber, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.time.Clock
import com.keepit.model.{ Library, LibraryKind, LibraryVisibility, User }
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.slick.jdbc.{ GetResult, StaticQuery }

@ImplementedBy(classOf[CuratorLibraryInfoRepoImpl])
trait CuratorLibraryInfoRepo extends DbRepo[CuratorLibraryInfo] with SeqNumberDbFunction[CuratorLibraryInfo] {
  def getByLibraryId(libraryId: Id[Library])(implicit session: RSession): Option[CuratorLibraryInfo]
  def getBySeqNum(start: SequenceNumber[CuratorLibraryInfo], maxBatchSize: Int)(implicit session: RSession): Seq[CuratorLibraryInfo]
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
    def name = column[String]("name", O.NotNull)
    def descriptionLength = column[Int]("description_length", O.NotNull)
    def * = (id.?, createdAt, updatedAt, libraryId, ownerId, memberCount, keepCount, visibility, lastKept.?,
      lastFollowed.?, state, kind, libraryLastUpdated, seq, name, descriptionLength) <>
      ((CuratorLibraryInfo.apply _).tupled, CuratorLibraryInfo.unapply)
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

  def getBySeqNum(start: SequenceNumber[CuratorLibraryInfo], maxBatchSize: Int)(implicit session: RSession): Seq[CuratorLibraryInfo] = try {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = if (db.dialect == H2DatabaseDialect) {
      sql"SELECT * FROM curator_library_info WHERE seq > ${start.value} ORDER BY seq LIMIT $maxBatchSize;"
    } else {
      sql"SELECT * FROM curator_library_info USE INDEX (curator_library_info_i_seq) WHERE seq > ${start.value} ORDER BY seq LIMIT $maxBatchSize;"
    }
    q.as[CuratorLibraryInfo].list
  } catch {
    case ex: Throwable =>
      log.error("getBySeqNum ERROR " + ex.getMessage, ex)
      Seq.empty
  }

  // update getCuratorLibraryInfoResult if you modify table
  private implicit val getCuratorLibraryInfoResult: GetResult[CuratorLibraryInfo] = GetResult { r =>
    CuratorLibraryInfo(
      id = r.<<[Option[Id[CuratorLibraryInfo]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      libraryId = r.<<[Id[Library]],
      ownerId = r.<<[Id[User]],
      memberCount = r.<<[Int],
      keepCount = r.<<[Int],
      visibility = r.<<[LibraryVisibility],
      lastKept = r.<<[Option[DateTime]],
      lastFollowed = r.<<[Option[DateTime]],
      state = r.<<[State[CuratorLibraryInfo]],
      kind = r.<<[LibraryKind],
      libraryLastUpdated = r.<<[DateTime],
      seq = r.<<[SequenceNumber[CuratorLibraryInfo]],
      name = r.<<[String],
      descriptionLength = r.<<[Int]
    )
  }

  private implicit val getLibraryKindResult: GetResult[LibraryKind] = GetResult { r =>
    LibraryKind(str = r.<<[String])
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
