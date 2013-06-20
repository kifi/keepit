package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.cache._
import scala.concurrent.duration._

case class Unscrapable(
  id: Option[Id[Unscrapable]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  pattern: String,
  state: State[Unscrapable] = UnscrapableStates.ACTIVE
) extends Model[Unscrapable] {

  def withId(id: Id[Unscrapable]) = this.copy(id = Some(id))
  def withState(newState: State[Unscrapable]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

}

import com.keepit.serializer.UnscrapableSerializer.unscrapableSerializer // Required implicit value
case class UnscrapableAllKey() extends Key[Seq[Unscrapable]] {
  override val version = 2
  val namespace = "unscrapable_all"
  def toKey(): String = "all"
}

class UnscrapableAllCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UnscrapableAllKey, Seq[Unscrapable]](innermostPluginSettings, innerToOuterPluginSettings:_*)

object UnscrapableStates extends States[Unscrapable]
