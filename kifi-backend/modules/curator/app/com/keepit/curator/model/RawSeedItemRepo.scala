package com.keepit.curator.model

import com.keepit.common.db.slick.{ DbRepo, SeqNumberFunction, SeqNumberDbFunction, DataBaseComponent, Database }
import com.keepit.common.db.slick.{ RepoWithDelete, DbRepoWithDelete }
import com.keepit.common.db.{ Id, DbSequenceAssigner, SequenceNumber, H2DatabaseDialect }
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier

import scala.concurrent.duration._
import scala.slick.jdbc.{ StaticQuery, GetResult }

import com.google.inject.{ ImplementedBy, Singleton, Inject }

import org.joda.time.DateTime

@ImplementedBy(classOf[RawSeedItemRepoImpl])
trait RawSeedItemRepo extends DbRepo[RawSeedItem] with SeqNumberFunction[RawSeedItem] with RepoWithDelete[RawSeedItem] {
  def getByUserIdAndUriIds(userId: Id[User], uris: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[RawSeedItem]
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[RawSeedItem]
  def getByUriIdAndUserId(uriId: Id[NormalizedURI], userIdOpt: Option[Id[User]])(implicit session: RSession): Option[RawSeedItem]
  def getDiscoverableBySeqNum(start: SequenceNumber[RawSeedItem], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getDiscoverableBySeqNumAndUser(start: SequenceNumber[RawSeedItem], userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getPopularDiscoverableBySeqNumAndUser(start: SequenceNumber[RawSeedItem], userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getRecent(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getRecentGeneric(maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def getFirstByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[RawSeedItem]
  def getByTopPriorScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem]
  def cleanupBatch(userId: Id[User])(implicit session: RWSession): Unit
}

@Singleton
class RawSeedItemRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[RawSeedItem] with RawSeedItemRepo with SeqNumberDbFunction[RawSeedItem] with DbRepoWithDelete[RawSeedItem] {

  import db.Driver.simple._

  type RepoImpl = RawSeedItemTable
  class RawSeedItemTable(tag: Tag) extends RepoTable[RawSeedItem](db, tag, "raw_seed_item") with SeqNumberColumn[RawSeedItem] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.NotNull) // set default url to empty string in db to avoid exceptions.
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def firstKept = column[DateTime]("first_kept", O.NotNull)
    def lastKept = column[DateTime]("last_kept", O.NotNull)
    def lastSeen = column[DateTime]("last_seen", O.NotNull)
    def priorScore = column[Option[Float]]("prior_score", O.Nullable)
    def timesKept = column[Int]("times_kept", O.NotNull)
    def discoverable = column[Boolean]("discoverable", O.NotNull)
    def * = (id.?, createdAt, updatedAt, seq, uriId, userId, firstKept, lastKept, lastSeen, priorScore, timesKept, discoverable, url) <> ((RawSeedItem.apply _).tupled, RawSeedItem.unapply _)
  }

  def table(tag: Tag) = new RawSeedItemTable(tag)
  initTable()

  //update getRawSeedItemResult if you modify table
  private implicit val getRawSeedItemResult: GetResult[RawSeedItem] = GetResult { r =>
    RawSeedItem(
      id = r.<<[Option[Id[RawSeedItem]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      seq = r.<<[SequenceNumber[RawSeedItem]],
      uriId = r.<<[Id[NormalizedURI]],
      userId = r.<<[Option[Id[User]]],
      firstKept = r.<<[DateTime],
      lastKept = r.<<[DateTime],
      lastSeen = r.<<[DateTime],
      priorScore = r.<<[Option[Float]],
      timesKept = r.<<[Int],
      discoverable = r.<<[Boolean],
      url = r.<<[String]
    )
  }

  def deleteCache(model: RawSeedItem)(implicit session: RSession): Unit = {}
  def invalidateCache(model: RawSeedItem)(implicit session: RSession): Unit = {}

  override def save(rawSeedItem: RawSeedItem)(implicit session: RWSession): RawSeedItem = {
    val toSave = rawSeedItem.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  def getByUserIdAndUriIds(userId: Id[User], uris: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.userId === userId && uris.toSet.contains(row.uriId)) yield row).list
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.uriId === uriId) yield row).list
  }

  def getFirstByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[RawSeedItem] = {
    (for (row <- rows if row.uriId === uriId && row.userId.isEmpty) yield row).firstOption
  }

  def getByUriIdAndUserId(uriId: Id[NormalizedURI], userIdOpt: Option[Id[User]])(implicit session: RSession): Option[RawSeedItem] = {
    userIdOpt.map { userId =>
      (for (row <- rows if row.uriId === uriId && row.userId === userId) yield row).firstOption
    } getOrElse {
      (for (row <- rows if row.uriId === uriId && row.userId.isEmpty) yield row).firstOption
    }
  }

  def getDiscoverableBySeqNumAndUser(start: SequenceNumber[RawSeedItem], userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = if (db.dialect == H2DatabaseDialect) {
      sql"SELECT * FROM raw_seed_item WHERE seq > ${start.value} AND (user_id=$userId OR user_id IS NULL) AND discoverable=1 ORDER BY seq LIMIT $maxBatchSize;"
    } else {
      sql"SELECT * FROM raw_seed_item USE INDEX (raw_seed_item_u_seq_user_id) WHERE seq > ${start.value} AND (user_id=$userId OR user_id IS NULL) AND discoverable=1 ORDER BY seq LIMIT $maxBatchSize;"
    }
    q.as[RawSeedItem].list
  }

  def getPopularDiscoverableBySeqNumAndUser(start: SequenceNumber[RawSeedItem], userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = if (db.dialect == H2DatabaseDialect) {
      sql"SELECT * FROM raw_seed_item WHERE seq > ${start.value} AND (user_id=$userId OR (user_id IS NULL AND times_kept>1)) AND discoverable=1 ORDER BY seq LIMIT $maxBatchSize;"
    } else {
      sql"SELECT * FROM raw_seed_item USE INDEX (raw_seed_item_u_seq_user_id) WHERE seq > ${start.value} AND (user_id=$userId OR (user_id IS NULL AND times_kept>1)) AND discoverable=1 ORDER BY seq LIMIT $maxBatchSize;"
    }
    q.as[RawSeedItem].list
  }

  def getDiscoverableBySeqNum(start: SequenceNumber[RawSeedItem], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = if (db.dialect == H2DatabaseDialect) {
      sql"SELECT * FROM raw_seed_item WHERE seq > ${start.value} AND (user_id IS NULL) AND discoverable=1 ORDER BY seq LIMIT $maxBatchSize;"
    } else {
      sql"SELECT * FROM raw_seed_item USE INDEX (raw_seed_item_u_seq_user_id) WHERE seq > ${start.value} AND (user_id IS NULL) AND discoverable=1 ORDER BY seq LIMIT $maxBatchSize;"
    }
    q.as[RawSeedItem].list
  }

  def getRecent(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.userId === userId || row.userId.isEmpty) yield row).sortBy(_.seq.desc).take(maxBatchSize).list
  }

  def getRecentGeneric(maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.userId.isEmpty) yield row).sortBy(_.seq.desc).take(maxBatchSize).list
  }

  def getByTopPriorScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[RawSeedItem] = {
    (for (row <- rows if row.userId === userId) yield row).sortBy(_.priorScore.desc).take(maxBatchSize).list
  }

  def cleanupBatch(userId: Id[User])(implicit session: RWSession): Unit = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val cutoff = currentDateTime.minus(org.joda.time.Duration.standardDays(10))
    sqlu"DELETE FROM raw_seed_item WHERE user_id=$userId AND updated_at<=$cutoff LIMIT 250".first
  }
}

@Singleton
class RawSeedItemSequenceNumberAssigner @Inject() (db: Database, repo: RawSeedItemRepo, airbrake: AirbrakeNotifier)
    extends DbSequenceAssigner[RawSeedItem](db, repo, airbrake) {
  override val batchSize: Int = 500
}

