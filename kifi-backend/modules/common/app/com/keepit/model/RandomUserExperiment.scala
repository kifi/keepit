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

case class ProbabilityDensity[+A](density: Seq[(A, Double)]) {
  require(density.forall(_._2 >= 0), "Probabilities must ne non-negative")
  require(density.map(_._2).sum <= 1, "Probabilities sum up to more than 1")
  val cumulative: Seq[(A, Double)] = {
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

case class RandomUserExperiment(
  id: Option[Id[RandomUserExperiment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[RandomUserExperiment] = RandomUserExperimentStates.ACTIVE,
  condition: Option[ExperimentType],
  salt: Double,
  density: ProbabilityDensity[ExperimentType]
) extends ModelWithState[RandomUserExperiment] {

  def withId(id: Id[RandomUserExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[RandomUserExperiment]) = this.copy(state = state)
  def isActive: Boolean = this.state == RandomUserExperimentStates.ACTIVE

  private def hash(userId: Id[User]): Double = ???

  def sample(userId: Id[User], userExperiments: Set[ExperimentType]): Option[ExperimentType] = {
    if (condition.isEmpty || userExperiments.contains(condition.get)) density.sample(hash(userId))
    else None
  }
}

object RandomUserExperiment {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[RandomUserExperiment]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[RandomUserExperiment]) and
    (__ \ 'condition).formatNullable[ExperimentType] and
    (__ \ 'salt).format[Double] and
    (__ \ 'distribution).format(ProbabilityDensity.format[ExperimentType])
  )(RandomUserExperiment.apply, unlift(RandomUserExperiment.unapply))
}

object RandomUserExperimentStates extends States[RandomUserExperiment]

trait RandomUserExperimentAllKey extends Key[Seq[RandomUserExperiment]] {
  override val version = 1
  val namespace = "random_user_experiment_all"
  def toKey(): String = "all"
}

object RandomUserExperimentAllKey extends RandomUserExperimentAllKey

class RandomUserExperimentAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RandomUserExperimentAllKey, Seq[RandomUserExperiment]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq[RandomUserExperiment])
