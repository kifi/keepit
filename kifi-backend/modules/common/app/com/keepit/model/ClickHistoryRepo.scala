package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock


@ImplementedBy(classOf[ClickHistoryRepoImpl])
trait ClickHistoryRepo extends Repo[ClickHistory] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[ClickHistory]
}

@Singleton
class ClickHistoryRepoImpl @Inject() (
                                       val db: DataBaseComponent,
                                       val clock: Clock,
                                       val clickCache: ClickHistoryUserIdCache)
  extends DbRepo[ClickHistory] with ClickHistoryRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[ClickHistory](db, "click_history") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def tableSize = column[Int]("table_size", O.NotNull)
    def filter = column[Array[Byte]]("filter", O.NotNull)
    def numHashFuncs = column[Int]("num_hash_funcs", O.NotNull)
    def minHits = column[Int]("min_hits", O.NotNull)
    def updatesCount = column[Int]("updates_count", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ state ~ userId ~ tableSize ~ filter ~ numHashFuncs ~ minHits ~ updatesCount <> (ClickHistory, ClickHistory.unapply _)
  }

  override def invalidateCache(clickHistory: ClickHistory)(implicit session: RSession) = {
    clickCache.set(ClickHistoryUserIdKey(clickHistory.userId), clickHistory)
    clickHistory
  }

  override def save(model: ClickHistory)(implicit session: RWSession): ClickHistory = {
    super.save(model.copy(updatesCount = model.updatesCount + 1))
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[ClickHistory] =
    clickCache.getOrElseOpt(ClickHistoryUserIdKey(userId)) {
      (for(b <- table if b.userId === userId && b.state === ClickHistoryStates.ACTIVE) yield b).firstOption
    }
}
