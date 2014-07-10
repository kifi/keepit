package com.keepit.maven.model

import com.keepit.common.db.slick.{ DbRepo, SeqNumberFunction, SeqNumberDbFunction, DataBaseComponent, Database }
import com.keepit.common.db.{ Id, DbSequenceAssigner }
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier

import scala.concurrent.duration._

import com.google.inject.{ ImplementedBy, Singleton, Inject }

import org.joda.time.DateTime

@ImplementedBy(classOf[RawSeedItemRepoImpl])
trait RawSeedItemRepo extends DbRepo[RawSeedItem] with SeqNumberFunction[RawSeedItem] {
}

@Singleton
class RawSeedItemRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[RawSeedItem] with RawSeedItemRepo with SeqNumberDbFunction[RawSeedItem] {

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
    def * = (id.?, createdAt, updatedAt, seq, uriId, userId.?, firstKept, lastKept, lastSeen, priorScore.?, timesKept) <> ((RawSeedItem.apply _).tupled, RawSeedItem.unapply _)
  }

  def table(tag: Tag) = new RawSeedItemTable(tag)
  initTable()

  def deleteCache(model: RawSeedItem)(implicit session: RSession): Unit = {}
  def invalidateCache(model: RawSeedItem)(implicit session: RSession): Unit = {}

  override def save(RawSeedItem: RawSeedItem)(implicit session: RWSession): RawSeedItem = {
    val toSave = RawSeedItem.copy(seq = deferredSeqNum())
    super.save(toSave)
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
