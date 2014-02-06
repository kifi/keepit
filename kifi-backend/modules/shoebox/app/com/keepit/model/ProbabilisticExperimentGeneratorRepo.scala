package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[ProbabilisticExperimentGeneratorRepoImpl])
trait ProbabilisticExperimentGeneratorRepo extends Repo[ProbabilisticExperimentGenerator] {
  def allActive()(implicit session: RSession): Seq[ProbabilisticExperimentGenerator]
}

@Singleton
class ProbabilisticExperimentGeneratorRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock,
  randomUserExperimentCache: RandomUserExperimentAllCache
) extends DbRepo[ProbabilisticExperimentGenerator] with ProbabilisticExperimentGeneratorRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[ProbabilisticExperimentGenerator](db, "probabilistic_experiment_generator") {
    def description = column[String]("description", O.NotNull)
    def condition = column[ExperimentType]("condition", O.Nullable)
    def salt = column[String]("salt", O.NotNull)
    def density = column[ProbabilityDensity[ExperimentType]]("density", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ state ~ description ~ condition.? ~ salt ~ density <> (ProbabilisticExperimentGenerator.apply _, ProbabilisticExperimentGenerator.unapply _)
  }

  def invalidateCache(model: ProbabilisticExperimentGenerator)(implicit session: RSession): Unit = {
    randomUserExperimentCache.set(RandomUserExperimentAllKey, allActive())
  }

  def deleteCache(model: ProbabilisticExperimentGenerator)(implicit session: RSession):Unit = {
    randomUserExperimentCache.remove(RandomUserExperimentAllKey)
  }

  def allActive()(implicit session: RSession): Seq[ProbabilisticExperimentGenerator] = {
    (for(f <- table if f.state === RandomUserExperimentStates.ACTIVE) yield f).list
  }
}

