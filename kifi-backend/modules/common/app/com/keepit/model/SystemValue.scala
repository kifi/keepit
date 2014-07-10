package com.keepit.model

import scala.concurrent.duration.Duration
import com.keepit.common.db.{ States, ModelWithState, State, Id }
import org.joda.time.DateTime
import com.keepit.common.cache.{ StringCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._

case class SystemValue(
    id: Option[Id[SystemValue]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    name: Name[SystemValue],
    value: String,
    state: State[SystemValue] = SystemValueStates.ACTIVE) extends ModelWithState[SystemValue] {

  def withId(id: Id[SystemValue]) = this.copy(id = Some(id))
  def withState(newState: State[SystemValue]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

case class SystemValueKey(name: Name[SystemValue]) extends Key[String] {
  override val version = 2
  val namespace = "system_value"
  def toKey(): String = name.name
}
class SystemValueCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[SystemValueKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object SystemValueStates extends States[SystemValue]
