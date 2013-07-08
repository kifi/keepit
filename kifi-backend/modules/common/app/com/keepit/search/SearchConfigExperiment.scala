package com.keepit.search

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.cache.{JsonCacheImpl, Key, FortyTwoCachePlugin}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{Model, State, States}
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.duration._

case class SearchConfigExperiment(
    id: Option[Id[SearchConfigExperiment]] = None,
    weight: Double = 0,
    description: String = "",
    config: SearchConfig = SearchConfig(Map[String, String]()),
    startedAt: Option[DateTime] = None,
    state: State[SearchConfigExperiment] = SearchConfigExperimentStates.CREATED,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime
    ) extends Model[SearchConfigExperiment] {
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
}

object SearchConfigExperiment {
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
}

sealed trait ActiveExperimentsKey extends Key[Seq[SearchConfigExperiment]] {
  val namespace = "search_config"
  override val version = 3
  def toKey() = "active_experiments"
}

object ActiveExperimentsKey extends ActiveExperimentsKey

class ActiveExperimentsCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ActiveExperimentsKey, Seq[SearchConfigExperiment]](innermostPluginSettings, innerToOuterPluginSettings:_*) {
    def getOrElseUpdate(value: => Seq[SearchConfigExperiment]): Seq[SearchConfigExperiment] = getOrElse(ActiveExperimentsKey)(value)
    def remove(): Unit = remove(ActiveExperimentsKey)
}

object SearchConfigExperimentStates extends States[SearchConfigExperiment] {
  val CREATED = State[SearchConfigExperiment]("created")
  val PAUSED = State[SearchConfigExperiment]("paused")
}
