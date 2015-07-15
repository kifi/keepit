package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.time.Clock
import com.keepit.common.db.State
import com.keepit.common.math.ProbabilityDensity

@ImplementedBy(classOf[ProbabilisticExperimentGeneratorRepoImpl])
trait ProbabilisticExperimentGeneratorRepo extends Repo[ProbabilisticExperimentGenerator] {
  def allActive()(implicit session: RSession): Seq[ProbabilisticExperimentGenerator]
  def getByName(name: Name[ProbabilisticExperimentGenerator], exclude: Option[State[ProbabilisticExperimentGenerator]] = Some(ProbabilisticExperimentGeneratorStates.INACTIVE))(implicit session: RSession): Option[ProbabilisticExperimentGenerator]
  def internByName(name: Name[ProbabilisticExperimentGenerator], density: ProbabilityDensity[ExperimentType], salt: Option[String] = None, condition: Option[ExperimentType] = None)(implicit session: RWSession): ProbabilisticExperimentGenerator
}

@Singleton
class ProbabilisticExperimentGeneratorRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    probabilisticExperimentGeneratorCache: ProbabilisticExperimentGeneratorAllCache) extends DbRepo[ProbabilisticExperimentGenerator] with ProbabilisticExperimentGeneratorRepo {

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

  def deleteCache(model: ProbabilisticExperimentGenerator)(implicit session: RSession): Unit = {
    probabilisticExperimentGeneratorCache.remove(ProbabilisticExperimentGeneratorAllKey)
  }

  def allActive()(implicit session: RSession): Seq[ProbabilisticExperimentGenerator] = {
    (for (f <- rows if f.state === ProbabilisticExperimentGeneratorStates.ACTIVE) yield f).list
  }

  def getByName(name: Name[ProbabilisticExperimentGenerator], exclude: Option[State[ProbabilisticExperimentGenerator]])(implicit session: RSession): Option[ProbabilisticExperimentGenerator] = {
    val q = (for (f <- rows if f.name === name && f.state =!= exclude.orNull) yield f)
    q.firstOption
  }

  def internByName(name: Name[ProbabilisticExperimentGenerator], density: ProbabilityDensity[ExperimentType], salt: Option[String], condition: Option[ExperimentType])(implicit session: RWSession): ProbabilisticExperimentGenerator = {
    val defaultSalt = name.name + "Salt"
    getByName(name, None) match {
      case Some(generator) if generator.state != ProbabilisticExperimentGeneratorStates.INACTIVE =>
        save(generator.copy(density = density, condition = condition orElse generator.condition, salt = salt getOrElse generator.salt))
      case Some(inactiveGenerator) => save(inactiveGenerator.copy(density = density, condition = condition, salt = salt getOrElse defaultSalt, state = ProbabilisticExperimentGeneratorStates.ACTIVE))
      case None => save(ProbabilisticExperimentGenerator(name = name, condition = condition, density = density, salt = salt getOrElse defaultSalt))
    }
  }
}

