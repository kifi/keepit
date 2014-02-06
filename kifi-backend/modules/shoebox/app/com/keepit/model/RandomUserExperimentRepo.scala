package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[RandomUserExperimentRepoImpl])
trait RandomUserExperimentRepo extends Repo[RandomUserExperiment] {
  def allActive()(implicit session: RSession): Seq[RandomUserExperiment]
}

@Singleton
class RandomUserExperimentRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock,
  randomUserExperimentCache: RandomUserExperimentAllCache
) extends DbRepo[RandomUserExperiment] with RandomUserExperimentRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[RandomUserExperiment](db, "random_user_experiment") {
    def condition = column[ExperimentType]("condition", O.Nullable)
    def salt = column[Double]("salt", O.NotNull)
    def density = column[ProbabilityDensity[ExperimentType]]("density", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ state ~ condition.? ~ salt ~ density <> (RandomUserExperiment.apply _, RandomUserExperiment.unapply _)
  }

  def invalidateCache(model: RandomUserExperiment)(implicit session: RSession): Unit = {
    randomUserExperimentCache.set(RandomUserExperimentAllKey, allActive())
  }

  def deleteCache(model: RandomUserExperiment)(implicit session: RSession):Unit = {
    randomUserExperimentCache.remove(RandomUserExperimentAllKey)
  }

  def allActive()(implicit session: RSession): Seq[RandomUserExperiment] = {
    (for(f <- table if f.state === RandomUserExperimentStates.ACTIVE) yield f).list
  }
}

