package com.keepit.curator.model

import com.keepit.common.db.slick.{ DbRepo, SeqNumberFunction, SeqNumberDbFunction, DataBaseComponent, Database }
import com.keepit.common.db.slick.{ RepoWithDelete, DbRepoWithDelete }
import com.keepit.common.db.{ Id, DbSequenceAssigner, SequenceNumber }
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.time.{ currentDateTime, Clock }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier

import scala.concurrent.duration._
import scala.slick.jdbc.StaticQuery

import com.google.inject.{ ImplementedBy, Singleton, Inject }

import org.joda.time.DateTime

@ImplementedBy(classOf[RawSeedItemRepoImpl])
trait RawSeedItemRepo extends DbRepo[RawSeedItem] with SeqNumberFunction[RawSeedItem] with RepoWithDelete[RawSeedItem] {
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[RawSeedItem]
  def getByUriIdAndUserId(uriId: Id[NormalizedURI], userIdOpt: Option[Id[User]])(implicit session: RSession): Option[RawSeedItem]
  def getBySeqNumAndUser(start: SequenceNumber[RawSeedItem], userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getRecent(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getRecentGeneric(maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getFirstByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[RawSeedItem]
  def getByTopPriorScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
}

@Singleton
class RawSeedItemRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[RawSeedItem] with RawSeedItemRepo with SeqNumberDbFunction[RawSeedItem] with DbRepoWithDelete[RawSeedItem] {

  import db.Driver.simple._

  private val sequence = db.getSequence[RawSeedItem]("raw_seed_item_sequence")

  type RepoImpl = RawSeedItemTable
  class RawSeedItemTable(tag: Tag) extends RepoTable[RawSeedItem](db, tag, "raw_seed_item") with SeqNumberColumn[RawSeedItem] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def firstKept = column[DateTime]("first_kept", O.NotNull)
    def lastKept = column[DateTime]("last_kept", O.NotNull)
    def lastSeen = column[DateTime]("last_seen", O.NotNull)
    def priorScore = column[Float]("prior_score", O.Nullable)
    def timesKept = column[Int]("times_kept", O.NotNull)
    def discoverable = column[Boolean]("discoverable", O.NotNull)
    def * = (id.?, createdAt, updatedAt, seq, uriId, userId.?, firstKept, lastKept, lastSeen, priorScore.?, timesKept, discoverable) <> ((RawSeedItem.apply _).tupled, RawSeedItem.unapply _)
  }

  def table(tag: Tag) = new RawSeedItemTable(tag)
  initTable()

  def deleteCache(model: RawSeedItem)(implicit session: RSession): Unit = {}
  def invalidateCache(model: RawSeedItem)(implicit session: RSession): Unit = {}

  override def save(RawSeedItem: RawSeedItem)(implicit session: RWSession): RawSeedItem = {
    val toSave = RawSeedItem.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.uriId === uriId) yield row).list
  }

  def getFirstByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[RawSeedItem] = {
    (for (row <- rows if row.uriId === uriId && row.userId.isNull) yield row).firstOption
  }

  def getByUriIdAndUserId(uriId: Id[NormalizedURI], userIdOpt: Option[Id[User]])(implicit session: RSession): Option[RawSeedItem] = {
    userIdOpt.map { userId =>
      (for (row <- rows if row.uriId === uriId && row.userId === userId) yield row).firstOption
    } getOrElse {
      (for (row <- rows if row.uriId === uriId && row.userId.isNull) yield row).firstOption
    }
  }

  def getBySeqNumAndUser(start: SequenceNumber[RawSeedItem], userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.seq > start && (row.userId === userId || row.userId.isNull)) yield row).sortBy(_.seq.asc).take(maxBatchSize).list
  }

  def getRecent(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.userId === userId || row.userId.isNull) yield row).sortBy(_.seq.desc).take(maxBatchSize).list
  }

  def getRecentGeneric(maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.userId.isNull) yield row).sortBy(_.seq.desc).take(maxBatchSize).list
  }

  def getByTopPriorScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.userId === userId) yield row).sortBy(_.priorScore.desc).take(maxBatchSize).list
  }

  override def assignSequenceNumbers(limit: Int = 20)(implicit session: RWSession): Int = {
    assignSequenceNumbers(sequence, "raw_seed_item", limit)
  }

  override def minDeferredSequenceNumber()(implicit session: RSession): Option[Long] = {
    import StaticQuery.interpolation
    sql"""select min(seq) from raw_seed_item where seq < 0""".as[Option[Long]].first
  }

}

trait RawSeedItemSequencingPlugin extends SequencingPlugin

class RawSeedItemSequencingPluginImpl @Inject() (
    override val actor: ActorInstance[RawSeedItemSequencingActor],
    override val scheduling: SchedulingProperties) extends RawSeedItemSequencingPlugin {

  override val interval: FiniteDuration = 60 seconds
}

@Singleton
class RawSeedItemSequenceNumberAssigner @Inject() (db: Database, repo: RawSeedItemRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[RawSeedItem](db, repo, airbrake)

class RawSeedItemSequencingActor @Inject() (
  assigner: RawSeedItemSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
