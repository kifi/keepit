package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.cache._
import scala.concurrent.duration._
import scala.Some
import net.codingwell.scalaguice.ScalaModule

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

case class BrowsingHistoryUserIdKey(userId: Id[User]) extends Key[BrowsingHistory] {
  override val version = 2
  val namespace = "browsing_history_by_userid"
  def toKey(): String = userId.id.toString
}

class BrowsingHistoryUserIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[BrowsingHistoryUserIdKey, BrowsingHistory](innermostPluginSettings, innerToOuterPluginSettings:_*)

object BrowsingHistoryStates extends States[BrowsingHistory]

trait BrowsingHistoryModule extends ScalaModule

