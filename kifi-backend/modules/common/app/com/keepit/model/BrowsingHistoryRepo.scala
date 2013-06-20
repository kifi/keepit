package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[BrowsingHistoryRepoImpl])
trait BrowsingHistoryRepo extends Repo[BrowsingHistory] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[BrowsingHistory]
}

@Singleton
class BrowsingHistoryRepoImpl @Inject() (
                                          val db: DataBaseComponent,
                                          val clock: Clock,
                                          val browsingCache: BrowsingHistoryUserIdCache)
  extends DbRepo[BrowsingHistory] with BrowsingHistoryRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[BrowsingHistory](db, "browsing_history") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def tableSize = column[Int]("table_size", O.NotNull)
    def filter = column[Array[Byte]]("filter", O.NotNull)
    def numHashFuncs = column[Int]("num_hash_funcs", O.NotNull)
    def minHits = column[Int]("min_hits", O.NotNull)
    def updatesCount = column[Int]("updates_count", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ state ~ userId ~ tableSize ~ filter ~ numHashFuncs ~ minHits ~ updatesCount <> (BrowsingHistory, BrowsingHistory.unapply _)
  }

  override def invalidateCache(browsingHistory: BrowsingHistory)(implicit session: RSession) = {
    browsingCache.set(BrowsingHistoryUserIdKey(browsingHistory.userId), browsingHistory)
    browsingHistory
  }

  override def save(model: BrowsingHistory)(implicit session: RWSession): BrowsingHistory = {
    super.save(model.copy(updatesCount = model.updatesCount + 1))
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[BrowsingHistory] =
    browsingCache.getOrElseOpt(BrowsingHistoryUserIdKey(userId)) {
      (for(b <- table if b.userId === userId && b.state === BrowsingHistoryStates.ACTIVE) yield b).firstOption
    }

}