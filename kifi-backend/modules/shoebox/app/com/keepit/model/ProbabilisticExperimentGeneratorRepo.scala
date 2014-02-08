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
  probabilisticExperimentGeneratorCache:ProbabilisticExperimentGeneratorAllCache
) extends DbRepo[ProbabilisticExperimentGenerator] with ProbabilisticExperimentGeneratorRepo {

  import db.Driver.simple._

  type RepoImpl = ProbabilisticExperimentGeneratorTable
  class ProbabilisticExperimentGeneratorTable(tag: Tag) extends RepoTable[ProbabilisticExperimentGenerator](db, tag, "probabilistic_experiment_generator") {
    def name = column[Name[ProbabilisticExperimentGenerator]]("name", O.NotNull)
    def condition = column[ExperimentType]("cond", O.Nullable)
    def salt = column[String]("salt", O.NotNull)
    def density = column[ProbabilityDensity[ExperimentType]]("density", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, name, condition.?, salt, density) <> ((ProbabilisticExperimentGenerator.apply _).tupled, ProbabilisticExperimentGenerator.unapply _)
  }

  def table(tag: Tag) = new ProbabilisticExperimentGeneratorTable(tag)
  initTable()

  def invalidateCache(model: ProbabilisticExperimentGenerator)(implicit session: RSession): Unit = {
    probabilisticExperimentGeneratorCache.set(ProbabilisticExperimentGeneratorAllKey, allActive())
  }

  def deleteCache(model: ProbabilisticExperimentGenerator)(implicit session: RSession):Unit = {
    probabilisticExperimentGeneratorCache.remove(ProbabilisticExperimentGeneratorAllKey)
  }

  def allActive()(implicit session: RSession): Seq[ProbabilisticExperimentGenerator] = {
    (for(f <- rows if f.state === ProbabilisticExperimentGeneratorStates.ACTIVE) yield f).list
  }
}

