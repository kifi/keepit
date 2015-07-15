package com.keepit.search

import com.keepit.common.cache.{ JsonCacheImpl, Key, FortyTwoCachePlugin }
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.common.db.{ ModelWithState, State, States }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.duration._
import com.keepit.common.cache.TransactionalCaching
import com.keepit.model.{ ProbabilisticExperimentGenerator, Name, ExperimentType }
import com.keepit.common.math.{ Probability, ProbabilityDensity }

case class SearchConfigExperiment(
    id: Option[Id[SearchConfigExperiment]] = None,
    weight: Double = 0,
    description: String = "",
    config: SearchConfig = SearchConfig(Map[String, String]()),
    startedAt: Option[DateTime] = None,
    state: State[SearchConfigExperiment] = SearchConfigExperimentStates.CREATED,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[SearchConfigExperiment] {
  def withId(id: Id[SearchConfigExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SearchConfigExperiment]) = {
    if (isStartable && state == SearchConfigExperimentStates.ACTIVE)
      this.copy(state = state, startedAt = Some(currentDateTime))
    else this.copy(state = state)
  }
  def isActive: Boolean = state == SearchConfigExperimentStates.ACTIVE
  def isStartable = Seq(SearchConfigExperimentStates.PAUSED, SearchConfigExperimentStates.CREATED) contains state
  def isRunning = state == SearchConfigExperimentStates.ACTIVE
  def isEditable = state == SearchConfigExperimentStates.CREATED
  def experiment: ExperimentType = SearchConfigExperiment.experimentType(id.get)
  require(weight >= 0, "Search experiment weight must be non-negative")
}

object SearchConfigExperiment {
  val probabilisticGenerator = Name[ProbabilisticExperimentGenerator]("searchConfigExperiment")
  def getDensity(searchExperiments: Seq[SearchConfigExperiment]): ProbabilityDensity[ExperimentType] = {
    ProbabilityDensity(searchExperiments.sortBy(_.id.get.id).map { se => Probability(se.experiment, se.weight) })
  }
  private implicit val idFormat = Id.format[SearchConfigExperiment]
  private implicit val stateFormat = State.format[SearchConfigExperiment]
  private implicit val searchConfigFormat = new Format[SearchConfig] {
    def reads(json: JsValue): JsResult[SearchConfig] =
      JsSuccess(SearchConfig(json.as[JsObject].fields.toMap.mapValues(_.as[String])))
    def writes(o: SearchConfig): JsValue =
      JsObject(o.params.mapValues(JsString(_)).toSeq)
  }

  implicit val format: Format[SearchConfigExperiment] = (
    (__ \ 'id).formatNullable[Id[SearchConfigExperiment]] and
    (__ \ 'weight).format[Double] and
    (__ \ 'description).format[String] and
    (__ \ 'config).format[SearchConfig] and
    (__ \ 'startedAt).formatNullable[DateTime] and
    (__ \ 'state).format[State[SearchConfigExperiment]] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat)
  )(SearchConfigExperiment.apply, unlift(SearchConfigExperiment.unapply))

  def experimentType(id: Id[SearchConfigExperiment]): ExperimentType = ExperimentType("SE-" + id.toString)
  val experimentTypePattern = """SE-(\d+)""".r
}

sealed trait ActiveExperimentsKey extends Key[Seq[SearchConfigExperiment]] {
  val namespace = "search_config"
  override val version = 3
  def toKey() = "active_experiments"
}

object ActiveExperimentsKey extends ActiveExperimentsKey

class ActiveExperimentsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[ActiveExperimentsKey, Seq[SearchConfigExperiment]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*) {
  def getOrElseUpdate(value: => Seq[SearchConfigExperiment])(implicit txn: TransactionalCaching): Seq[SearchConfigExperiment] = this.getOrElse(ActiveExperimentsKey)(value)
  def remove()(implicit txn: TransactionalCaching): Unit = this.remove(ActiveExperimentsKey)
}

object SearchConfigExperimentStates extends States[SearchConfigExperiment] {
  val CREATED = State[SearchConfigExperiment]("created")
  val PAUSED = State[SearchConfigExperiment]("paused")
}
