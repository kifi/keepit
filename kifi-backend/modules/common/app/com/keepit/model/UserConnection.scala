package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.db.State
import com.keepit.common.db.States
import com.keepit.common.time._
import com.keepit.common.time.currentDateTime
import com.keepit.common.cache.{JsonCacheImpl, Key, FortyTwoCachePlugin}
import scala.concurrent.duration._
import com.keepit.serializer.TraversableFormat

case class UserConnection(
    id: Option[Id[UserConnection]] = None,
    user1: Id[User],
    user2: Id[User],
    state: State[UserConnection] = UserConnectionStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime
  ) extends Model[UserConnection] {
  def withId(id: Id[UserConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserConnection]) = copy(state = state)
}

case class UserConnectionKey(userId: Id[User]) extends Key[Set[Id[User]]] {
  override val version = 2
  val namespace = "user_connection_key"
  def toKey(): String = userId.id.toString
}

class UserConnectionIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserConnectionKey, Set[Id[User]]](innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.set(Id.format[User]))

object UserConnectionStates extends States[UserConnection] {
  val UNFRIENDED = State[UserConnection]("unfriended")
}

