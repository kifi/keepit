package com.keepit.search

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{Model, State, States}
import com.keepit.common.time._
import org.joda.time.DateTime
import java.util.concurrent.atomic.AtomicReference

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

@ImplementedBy(classOf[SearchConfigExperimentRepoImpl])
trait SearchConfigExperimentRepo extends Repo[SearchConfigExperiment] {
  def getActive()(implicit session: RSession): Seq[SearchConfigExperiment]
  def getNotInactive()(implicit session: RSession): Seq[SearchConfigExperiment]
}

@Singleton
class SearchConfigExperimentRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[SearchConfigExperiment] with SearchConfigExperimentRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[SearchConfigExperiment](db, "search_config_experiment") {
    def weight = column[Double]("weight", O.NotNull)
    def description = column[String]("description", O.NotNull)
    def config = column[SearchConfig]("config", O.NotNull)
    def startedAt = column[Option[DateTime]]("started_at", O.NotNull)
    def * = id.? ~ weight ~ description ~ config ~ startedAt ~ state ~ createdAt ~ updatedAt <>
        (SearchConfigExperiment.apply _, SearchConfigExperiment.unapply _)
  }

  def getActive()(implicit session: RSession): Seq[SearchConfigExperiment] =
    (for (v <- table if v.state === SearchConfigExperimentStates.ACTIVE) yield v).list

  def getNotInactive()(implicit session: RSession): Seq[SearchConfigExperiment] =
    (for (v <- table if v.state =!= SearchConfigExperimentStates.INACTIVE) yield v).list
}

object SearchConfigExperimentStates extends States[SearchConfigExperiment] {
  val CREATED = State[SearchConfigExperiment]("created")
  val PAUSED = State[SearchConfigExperiment]("paused")
}
