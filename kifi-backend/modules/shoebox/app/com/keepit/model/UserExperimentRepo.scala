package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock
import scala.Some

@ImplementedBy(classOf[UserExperimentRepoImpl])
trait UserExperimentRepo extends Repo[UserExperiment] {
  def getUserExperiments(userId: Id[User])(implicit session: RSession): Set[State[ExperimentType]]
  def get(userId: Id[User], experiment: State[ExperimentType],
          excludeState: Option[State[UserExperiment]] = Some(UserExperimentStates.INACTIVE))
         (implicit session: RSession): Option[UserExperiment]
  def getByType(experiment: State[ExperimentType])(implicit session: RSession): Seq[UserExperiment]
  def hasExperiment(userId: Id[User], experimentType: State[ExperimentType])(implicit session: RSession): Boolean
}

@Singleton
class UserExperimentRepoImpl @Inject()(
    val db: DataBaseComponent,
    val clock: Clock,
    userExperimentCache: UserExperimentCache)
  extends DbRepo[UserExperiment] with UserExperimentRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UserExperiment](db, "user_experiment") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def experimentType = column[State[ExperimentType]]("experiment_type", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ experimentType ~ state <> (UserExperiment,
      UserExperiment.unapply _)
  }

  def getUserExperiments(userId: Id[User])(implicit session: RSession): Set[State[ExperimentType]] = {
    userExperimentCache.getOrElse(UserExperimentUserIdKey(userId)) {
      (for(f <- table if f.userId === userId && f.state === UserExperimentStates.ACTIVE) yield f.experimentType).list
    } toSet
  }

  def get(userId: Id[User], experimentType: State[ExperimentType],
          excludeState: Option[State[UserExperiment]] = Some(UserExperimentStates.INACTIVE))
         (implicit session: RSession): Option[UserExperiment] = {
    (for {
      f <- table if f.userId === userId && f.experimentType === experimentType && f.state =!= excludeState.orNull
    } yield f).firstOption
  }

  def hasExperiment(userId: Id[User], experimentType: State[ExperimentType])(implicit session: RSession): Boolean = {
    getUserExperiments(userId).contains(experimentType)
  }

  override def invalidateCache(model: UserExperiment)(implicit session: RSession): UserExperiment = {
    userExperimentCache.remove(UserExperimentUserIdKey(model.userId))
    model
  }

  def getByType(experiment: State[ExperimentType])(implicit session: RSession): Seq[UserExperiment] = {
    val q = for {
      f <- table if f.experimentType === experiment && f.state === UserExperimentStates.ACTIVE
    } yield f
    q.list
  }

}
