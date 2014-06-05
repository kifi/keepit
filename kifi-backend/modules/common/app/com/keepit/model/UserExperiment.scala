package com.keepit.model

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.concurrent.duration._
import com.keepit.serializer.TraversableFormat
import play.api.libs.json._

case class UserExperiment (
  id: Option[Id[UserExperiment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  experimentType: ExperimentType,
  state: State[UserExperiment] = UserExperimentStates.ACTIVE
) extends ModelWithState[UserExperiment] {
  def withId(id: Id[UserExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserExperiment]) = this.copy(state = state)
  def isActive: Boolean = this.state == UserExperimentStates.ACTIVE
}

final case class ExperimentType(value: String) {
  override def toString = value
}

object ExperimentType {

  implicit val format: Format[ExperimentType] = Format(
    __.read[String].map(ExperimentType(_)),
    new Writes[ExperimentType]{ def writes(o: ExperimentType) = JsString(o.value)}
  )

  val ADMIN = ExperimentType("admin")
  val AUTO_GEN = ExperimentType("autogen")
  val FAKE = ExperimentType("fake")
  val NO_SEARCH_EXPERIMENTS = ExperimentType("no search experiments")
  val NOT_SENSITIVE = ExperimentType("not sensitive")
  val CAN_MESSAGE_ALL_USERS = ExperimentType("can message all users")
  val DEMO = ExperimentType("demo")
  val EXTENSION_LOGGING = ExperimentType("extension_logging")
  val SHOW_HIT_SCORES = ExperimentType("show_hit_scores")
  val SHOW_DISCUSSIONS = ExperimentType("show_discussions")
  val MOBILE_REDIRECT = ExperimentType("mobile_redirect")
  val HELPRANK = ExperimentType("helprank")
  val GUIDE = ExperimentType("guide")
  val KIFI_BLACK = ExperimentType("kifi_black")

  val _ALL = ADMIN :: AUTO_GEN :: FAKE :: NO_SEARCH_EXPERIMENTS :: NOT_SENSITIVE ::
    CAN_MESSAGE_ALL_USERS :: DEMO :: EXTENSION_LOGGING :: SHOW_HIT_SCORES :: SHOW_DISCUSSIONS ::
    MOBILE_REDIRECT :: HELPRANK :: GUIDE :: KIFI_BLACK :: Nil

  val _ALL_MAP: Map[String, ExperimentType] = _ALL map { e => e.value -> e } toMap

  def get(str: String): ExperimentType = _ALL_MAP(str.toLowerCase.trim)

  def getUserStatus(experiments: Set[ExperimentType]): String =
    if (experiments.contains(FAKE)) FAKE.value
    else if (experiments.contains(ADMIN)) ADMIN.value
    else "standard"

  def getTestExperiments(email: EmailAddress): Set[ExperimentType] = {
    if (email.isTestEmail()) {
      if (email.isAutoGenEmail()) Set(ExperimentType.FAKE, ExperimentType.AUTO_GEN)
      else Set(ExperimentType.FAKE)
    } else
      Set.empty
  }
}

object UserExperimentStates extends States[UserExperiment] {
  implicit val formatter = State.format[ExperimentType]
}

case class UserExperimentUserIdKey(userId: Id[User]) extends Key[Seq[ExperimentType]] {
  override val version = 2
  val namespace = "user_experiment_user_id"
  def toKey(): String = userId.id.toString
}

class UserExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[UserExperimentUserIdKey, Seq[ExperimentType]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq[ExperimentType])
