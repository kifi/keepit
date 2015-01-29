package com.keepit.model

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

case class UserPersona(
    id: Option[Id[UserPersona]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    personaId: Id[Persona],
    state: State[UserPersona] = UserPersonaStates.ACTIVE) extends ModelWithState[UserPersona] {
  def withId(id: Id[UserPersona]): UserPersona = copy(id = Some(id))
  def withUpdateTime(now: DateTime): UserPersona = copy(updatedAt = now)
}

object UserPersonaStates extends States[UserPersona]

@json case class UserActivePersonas(personas: Seq[Id[Persona]], updatedAt: Seq[DateTime])

case class UserActivePersonasKey(id: Id[User]) extends Key[UserActivePersonas] {
  override val version = 1
  val namespace = "user_active_personas"
  def toKey(): String = id.id.toString
}

class UserActivePersonasCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserActivePersonasKey, UserActivePersonas](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
