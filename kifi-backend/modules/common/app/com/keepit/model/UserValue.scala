package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.cache.{StringCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration

case class UserValue(
  id: Option[Id[UserValue]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  name: String,
  value: String,
  state: State[UserValue] = UserValueStates.ACTIVE
) extends Model[UserValue] {

  def withId(id: Id[UserValue]) = this.copy(id = Some(id))
  def withState(newState: State[UserValue]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

case class UserValueKey(userId: Id[User], key: String) extends Key[String] {
  override val version = 2
  val namespace = "uservalue"
  def toKey(): String = userId.id + "_" + key
}
class UserValueCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[UserValueKey](innermostPluginSettings, innerToOuterPluginSettings:_*)

object UserValueStates extends States[UserValue]
