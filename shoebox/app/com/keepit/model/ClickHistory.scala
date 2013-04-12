package com.keepit.model

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api._
import com.keepit.common.cache.{FortyTwoCache, FortyTwoCachePlugin, Key}
import com.keepit.serializer.ClickHistoryBinarySerializer
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._

case class ClickHistory (
                    id: Option[Id[ClickHistory]] = None,
                    createdAt: DateTime = currentDateTime,
                    updatedAt: DateTime = currentDateTime,
                    state: State[ClickHistory] = ClickHistoryStates.ACTIVE,
                    userId: Id[User],
                    tableSize: Int,
                    filter: Array[Byte],
                    numHashFuncs: Int,
                    minHits: Int,
                    updatesCount: Int = 0
                    ) extends Model[ClickHistory] {
  def withFilter(filter: Array[Byte]) = this.copy(filter = filter)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[ClickHistory]) = this.copy(id = Some(id))
}

@ImplementedBy(classOf[ClickHistoryRepoImpl])
trait ClickHistoryRepo extends Repo[ClickHistory] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[ClickHistory]
}

case class ClickHistoryUserIdKey(userId: Id[User]) extends Key[ClickHistory] {
  val namespace = "click_history_by_userid"
  def toKey(): String = userId.id.toString
}
class ClickHistoryUserIdCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[ClickHistoryUserIdKey, ClickHistory] {
  val ttl = 7 days
  def deserialize(obj: Any): ClickHistory = ClickHistoryBinarySerializer.clickHistoryBinarySerializer.reads(obj.asInstanceOf[Array[Byte]])
  def serialize(clickHistory: ClickHistory) = ClickHistoryBinarySerializer.clickHistoryBinarySerializer.writes(clickHistory)
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

  override lazy val table = new RepoTable[ClickHistory](db, "click_history") {
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

object ClickHistoryStates extends States[ClickHistory]
