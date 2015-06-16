package com.keepit.model

import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.json.TraversableFormat
import scala.util.hashing.MurmurHash3
import com.keepit.common.math.ProbabilityDensity

trait UserExperimentGenerator {
  def apply(userId: Id[User], existingExperiments: Set[ExperimentType]): Option[ExperimentType]
}

case class ProbabilisticExperimentGenerator(
    id: Option[Id[ProbabilisticExperimentGenerator]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ProbabilisticExperimentGenerator] = ProbabilisticExperimentGeneratorStates.ACTIVE,
    name: Name[ProbabilisticExperimentGenerator],
    condition: Option[ExperimentType],
    salt: String,
    density: ProbabilityDensity[ExperimentType]) extends ModelWithState[ProbabilisticExperimentGenerator] with UserExperimentGenerator {

  def withId(id: Id[ProbabilisticExperimentGenerator]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[ProbabilisticExperimentGenerator]) = this.copy(state = state)
  def isActive: Boolean = this.state == ProbabilisticExperimentGeneratorStates.ACTIVE

  protected[model] def hash(userId: Id[User]): Double = {
    val h = MurmurHash3.stringHashing.hash(salt + userId.toString)
    (Int.MaxValue.toDouble - h) / (Int.MaxValue.toDouble - Int.MinValue.toDouble)
  }

  def apply(userId: Id[User], userExperiments: Set[ExperimentType]): Option[ExperimentType] = {
    if (condition.isEmpty || userExperiments.contains(condition.get)) density.sample(hash(userId))
    else None
  }
}

object ProbabilisticExperimentGenerator {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ProbabilisticExperimentGenerator]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[ProbabilisticExperimentGenerator]) and
    (__ \ 'name).format(Name.format[ProbabilisticExperimentGenerator]) and
    (__ \ 'condition).formatNullable[ExperimentType] and
    (__ \ 'salt).format[String] and
    (__ \ 'density).format(ProbabilityDensity.format[ExperimentType])
  )(ProbabilisticExperimentGenerator.apply, unlift(ProbabilisticExperimentGenerator.unapply))
}

object ProbabilisticExperimentGeneratorStates extends States[ProbabilisticExperimentGenerator]

trait ProbabilisticExperimentGeneratorAllKey extends Key[Seq[ProbabilisticExperimentGenerator]] {
  override val version = 3
  val namespace = "probabilistic_experiment_generator_all"
  def toKey(): String = "all"
}

object ProbabilisticExperimentGeneratorAllKey extends ProbabilisticExperimentGeneratorAllKey

class ProbabilisticExperimentGeneratorAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ProbabilisticExperimentGeneratorAllKey, Seq[ProbabilisticExperimentGenerator]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(TraversableFormat.seq[ProbabilisticExperimentGenerator])
