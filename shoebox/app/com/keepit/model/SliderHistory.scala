package com.keepit.model

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import play.api.libs.json._
import com.keepit.common.cache.{FortyTwoCache, FortyTwoCachePlugin, Key}
import com.keepit.serializer.SliderHistoryBinarySerializer
import scala.concurrent.duration._
import com.keepit.search.MultiHashFilter

case class SliderHistory (
  id: Option[Id[SliderHistory]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SliderHistory] = SliderHistoryStates.ACTIVE,
  userId: Id[User],
  tableSize: Int,
  filter: Array[Byte],
  numHashFuncs: Int,
  minHits: Int,
  updatesCount: Int = 0
  ) extends Model[SliderHistory] {
  def withFilter(filter: Array[Byte]) = this.copy(filter = filter)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[SliderHistory]) = this.copy(id = Some(id))
}

@ImplementedBy(classOf[SliderHistoryRepoImpl])
trait SliderHistoryRepo extends Repo[SliderHistory] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[SliderHistory]
}

case class SliderHistoryUserIdKey(userId: Id[User]) extends Key[SliderHistory] {
  val namespace = "slider_history_by_userid"
  def toKey(): String = userId.id.toString
}
class SliderHistoryUserIdCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[SliderHistoryUserIdKey, SliderHistory] {
  val ttl = 7 days
  def deserialize(obj: Any): SliderHistory = SliderHistoryBinarySerializer.sliderHistoryBinarySerializer.reads(obj.asInstanceOf[Array[Byte]])
  def serialize(sliderHistory: SliderHistory) = SliderHistoryBinarySerializer.sliderHistoryBinarySerializer.writes(sliderHistory)
}

@Singleton
class SliderHistoryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val browsingCache: SliderHistoryUserIdCache)
    extends DbRepo[SliderHistory] with SliderHistoryRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[SliderHistory](db, "browsing_history") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def tableSize = column[Int]("table_size", O.NotNull)
    def filter = column[Array[Byte]]("filter", O.NotNull)
    def numHashFuncs = column[Int]("num_hash_funcs", O.NotNull)
    def minHits = column[Int]("min_hits", O.NotNull)
    def updatesCount = column[Int]("updates_count", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ state ~ userId ~ tableSize ~ filter ~ numHashFuncs ~ minHits ~ updatesCount <> (SliderHistory, SliderHistory.unapply _)
  }

  override def invalidateCache(sliderHistory: SliderHistory)(implicit session: RSession) = {
    browsingCache.set(SliderHistoryUserIdKey(sliderHistory.userId), sliderHistory)
    sliderHistory
  }

  override def save(model: SliderHistory)(implicit session: RWSession): SliderHistory = {
    super.save(model.copy(updatesCount = model.updatesCount + 1))
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[SliderHistory] =
    browsingCache.getOrElseOpt(SliderHistoryUserIdKey(userId)) {
      (for(b <- table if b.userId === userId && b.state === SliderHistoryStates.ACTIVE) yield b).firstOption
    }

}

object SliderHistoryTracker {
  def apply(filterSize: Int, numHashFuncs: Int, minHits: Int) = {
    new SliderHistoryTracker(filterSize, numHashFuncs, minHits)
  }
}

class SliderHistoryTracker(tableSize: Int, numHashFuncs: Int, minHits: Int) {

  def add(userId: Id[User], uriId: Id[NormalizedURI]): SliderHistory = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    inject[Database].readWrite { implicit session =>
      val sliderHistoryRepo = inject[SliderHistoryRepo]
      sliderHistoryRepo.save(sliderHistoryRepo.getByUserId(userId) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          SliderHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
  }

  def getMultiHashFilter(userId: Id[User]) = {
    val sliderHistoryRepo = inject[SliderHistoryRepo]
    inject[Database].readOnly { implicit session =>
      sliderHistoryRepo.getByUserId(userId) match {
        case Some(sliderHistory) =>
          new MultiHashFilter(sliderHistory.tableSize, sliderHistory.filter, sliderHistory.numHashFuncs, sliderHistory.minHits)
        case None =>
          MultiHashFilter(tableSize, numHashFuncs, minHits)
      }
    }
  }
}


object SliderHistoryStates extends States[SliderHistory]
