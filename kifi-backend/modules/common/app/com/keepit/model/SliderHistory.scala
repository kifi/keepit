package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.cache.{ BinaryCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration

case class SliderHistory(
    id: Option[Id[SliderHistory]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SliderHistory] = SliderHistoryStates.ACTIVE,
    userId: Id[User],
    tableSize: Int,
    filter: Array[Byte],
    numHashFuncs: Int,
    minHits: Int,
    updatesCount: Int = 0) extends ModelWithState[SliderHistory] {
  def withFilter(filter: Array[Byte]) = this.copy(filter = filter)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[SliderHistory]) = this.copy(id = Some(id))
}

case class SliderHistoryUserIdKey(userId: Id[User]) extends Key[SliderHistory] {
  override val version = 3
  val namespace = "slider_history_by_userid"
  def toKey(): String = userId.id.toString
}

class SliderHistoryUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[SliderHistoryUserIdKey, SliderHistory](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object SliderHistoryStates extends States[SliderHistory]
