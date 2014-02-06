package com.keepit.model

import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.serializer.TraversableFormat
import scala.util.hashing.MurmurHash3

case class ProbabilityDensity[+A](density: Seq[(A, Double)]) {
  require(density.forall(_._2 >= 0), "Probabilities must ne non-negative")
  require(density.map(_._2).sum <= 1, "Probabilities sum up to more than 1")
  val cumulative: Seq[(A, Double)] = { // The order of the density sequence is implied to compute the CDF
    var cdf = 0.0
    density.map { case (outcome, probability) =>
      cdf += probability
      outcome -> cdf
    }
  }

  def sample(x: Double): Option[A] = cumulative.collectFirst { case (outcome, cdf) if cdf <= x => outcome }
}

object ProbabilityDensity {
  def format[A](implicit outcomeFormat: Format[A]): Format[ProbabilityDensity[A]] = Format(
    Json.reads[JsArray].fmap { case JsArray(density) => ProbabilityDensity(density.sliding(2, 2).map { case Seq(outcome, JsNumber(probability)) => (outcome.as[A], probability.toDouble)}.toSeq) },
    Writes({ density: ProbabilityDensity[A] => JsArray(density.density.flatMap { case (outcome, probability) => Seq(Json.toJson(outcome), JsNumber(probability)) }) })
  )
}

case class ProbabilisticExperimentGenerator(
  id: Option[Id[ProbabilisticExperimentGenerator]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[ProbabilisticExperimentGenerator] = RandomUserExperimentStates.ACTIVE,
  description: String,
  condition: Option[ExperimentType],
  salt: String,
  density: ProbabilityDensity[ExperimentType]
) extends ((Id[User], Set[ExperimentType]) => Option[ExperimentType]) with ModelWithState[ProbabilisticExperimentGenerator]  {

  def withId(id: Id[ProbabilisticExperimentGenerator]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[ProbabilisticExperimentGenerator]) = this.copy(state = state)
  def isActive: Boolean = this.state == RandomUserExperimentStates.ACTIVE

  private def hash(userId: Id[User]): Double = {
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
    (__ \ 'description).format[String] and
    (__ \ 'condition).formatNullable[ExperimentType] and
    (__ \ 'salt).format[String] and
    (__ \ 'distribution).format(ProbabilityDensity.format[ExperimentType])
  )(ProbabilisticExperimentGenerator.apply, unlift(ProbabilisticExperimentGenerator.unapply))
}

object RandomUserExperimentStates extends States[ProbabilisticExperimentGenerator]

trait RandomUserExperimentAllKey extends Key[Seq[ProbabilisticExperimentGenerator]] {
  override val version = 1
  val namespace = "random_user_experiment_all"
  def toKey(): String = "all"
}

object RandomUserExperimentAllKey extends RandomUserExperimentAllKey

class RandomUserExperimentAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RandomUserExperimentAllKey, Seq[ProbabilisticExperimentGenerator]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq[ProbabilisticExperimentGenerator])
