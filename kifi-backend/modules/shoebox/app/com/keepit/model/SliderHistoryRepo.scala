package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.search.tracking.MultiHashFilter

@ImplementedBy(classOf[SliderHistoryRepoImpl])
trait SliderHistoryRepo extends Repo[SliderHistory] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[SliderHistory]
}

@Singleton
class SliderHistoryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val browsingCache: SliderHistoryUserIdCache)
    extends DbRepo[SliderHistory] with SliderHistoryRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = SliderHistoryTable
  class SliderHistoryTable(tag: Tag) extends RepoTable[SliderHistory](db, tag, "slider_history") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def tableSize = column[Int]("table_size", O.NotNull)
    def filter = column[Array[Byte]]("filter", O.NotNull)
    def numHashFuncs = column[Int]("num_hash_funcs", O.NotNull)
    def minHits = column[Int]("min_hits", O.NotNull)
    def updatesCount = column[Int]("updates_count", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, userId, tableSize, filter, numHashFuncs, minHits, updatesCount) <> ((SliderHistory.apply _).tupled, SliderHistory.unapply _)
  }

  def table(tag: Tag) = new SliderHistoryTable(tag)
  initTable()

  override def invalidateCache(sliderHistory: SliderHistory)(implicit session: RSession): Unit = {
    browsingCache.set(SliderHistoryUserIdKey(sliderHistory.userId), sliderHistory)
  }

  override def deleteCache(sliderHistory: SliderHistory)(implicit session: RSession): Unit = {
    browsingCache.remove(SliderHistoryUserIdKey(sliderHistory.userId))
  }

  override def save(model: SliderHistory)(implicit session: RWSession): SliderHistory = {
    super.save(model.copy(updatesCount = model.updatesCount + 1))
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[SliderHistory] =
    browsingCache.getOrElseOpt(SliderHistoryUserIdKey(userId)) {
      (for (b <- rows if b.userId === userId && b.state === SliderHistoryStates.ACTIVE) yield b).firstOption
    }

}

trait SliderHistoryTracker {
  def add(userId: Id[User], uriId: Id[NormalizedURI]): SliderHistory
  def getMultiHashFilter(userId: Id[User]): MultiHashFilter[SliderHistory]
}

class SliderHistoryTrackerImpl(sliderHistoryRepo: SliderHistoryRepo, db: Database, tableSize: Int, numHashFuncs: Int, minHits: Int) extends SliderHistoryTracker with Logging {

  def add(userId: Id[User], uriId: Id[NormalizedURI]): SliderHistory = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    db.readWrite(attempts = 3) { implicit session =>
      sliderHistoryRepo.save(sliderHistoryRepo.getByUserId(userId) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          SliderHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
  }

  def getMultiHashFilter(userId: Id[User]) = {
    db.readOnlyMaster { implicit session =>
      sliderHistoryRepo.getByUserId(userId) match {
        case Some(sliderHistory) =>
          new MultiHashFilter(sliderHistory.tableSize, sliderHistory.filter, sliderHistory.numHashFuncs, sliderHistory.minHits)
        case None =>
          MultiHashFilter(tableSize, numHashFuncs, minHits)
      }
    }
  }
}
