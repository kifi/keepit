package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
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
) extends ModelWithState[UserValue] {
  def withId(id: Id[UserValue]) = this.copy(id = Some(id))
  def withState(newState: State[UserValue]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

case class UserValueKey(userId: Id[User], key: String) extends Key[String] {
  override val version = 2
  val namespace = "uservalue"
  def toKey(): String = userId.id + "_" + key
}
class UserValueCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[UserValueKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

object UserValueStates extends States[UserValue]

trait Gender
object Gender {
  case object Male extends Gender
  case object Female extends Gender
  val key = "gender"
  def apply(gender: String): Gender = gender.toLowerCase match {
    case "male" => Male
    case "female" => Female
  }
}

object UserValues {

  trait UserValueHandler[T] {
    def name: String
    def get(valOpt: Option[String]): T
    def getFromMap(valMap: Map[String, Option[String]]): T = get(valMap(name))
  }

  case class UserValueBooleanHandler(override val name: String, default: Boolean) extends UserValueHandler[Boolean] {
    def get(valOpt: Option[String]): Boolean = valOpt.map(_.toBoolean).getOrElse(default)
  }

  case class UserValueIntHandler(override val name: String, default: Int) extends UserValueHandler[Int] {
    def get(valOpt: Option[String]): Int = valOpt.map(_.toInt).getOrElse(default)
  }

  val enterToSend = UserValueBooleanHandler("enter_to_send", true)
  val maxResults = UserValueIntHandler("ext_max_results", 1)
  val showKeeperIntro = UserValueBooleanHandler("ext_show_keeper_intro", false)
  val showSearchIntro = UserValueBooleanHandler("ext_show_search_intro", false)
  val showFindFriends = UserValueBooleanHandler("ext_show_find_friends", false)

  val UserInitPrefs: Seq[String] = Seq(enterToSend, maxResults, showKeeperIntro, showSearchIntro, showFindFriends).map(_.name)
}