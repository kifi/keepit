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
import com.keepit.common.cache._
import com.keepit.serializer.BrowsingHistoryBinarySerializer
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import scala.Some

case class BrowsingHistory (
                    id: Option[Id[BrowsingHistory]] = None,
                    createdAt: DateTime = currentDateTime,
                    updatedAt: DateTime = currentDateTime,
                    state: State[BrowsingHistory] = BrowsingHistoryStates.ACTIVE,
                    userId: Id[User],
                    tableSize: Int,
                    filter: Array[Byte],
                    numHashFuncs: Int,
                    minHits: Int,
                    updatesCount: Int = 0
                    ) extends Model[BrowsingHistory] {
  def withFilter(filter: Array[Byte]) = this.copy(filter = filter)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[BrowsingHistory]) = this.copy(id = Some(id))
}

@ImplementedBy(classOf[BrowsingHistoryRepoImpl])
trait BrowsingHistoryRepo extends Repo[BrowsingHistory] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[BrowsingHistory]
}

case class BrowsingHistoryUserIdKey(userId: Id[User]) extends Key[BrowsingHistory] {
  val namespace = "browsing_history_by_userid"
  def toKey(): String = userId.id.toString
}

class BrowsingHistoryUserIdCache @Inject() (innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin)
  extends BinaryCacheImpl[BrowsingHistoryUserIdKey, BrowsingHistory]((innerRepo, 10 seconds), (outerRepo, 7 days))

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

object BrowsingHistoryStates extends States[BrowsingHistory]
