package com.keepit.commanders

import com.keepit.model.{
  ExperimentType,
  User,
  UserExperimentRepo,
  Name,
  ProbabilisticExperimentGeneratorRepo,
  ProbabilisticExperimentGenerator,
  ProbabilisticExperimentGeneratorAllCache
}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.google.inject.{ Inject, Singleton }

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.UserExperiment
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.math.ProbabilityDensity
import com.keepit.common.logging.Logging

@Singleton //The Singleton is very importatnt here. There is a cache on the object.
class LocalUserExperimentCommander @Inject() (
  userExperimentRepo: UserExperimentRepo,
  db: Database,
  generatorRepo: ProbabilisticExperimentGeneratorRepo,
  protected val generatorCache: ProbabilisticExperimentGeneratorAllCache,
  protected val monitoredAwait: MonitoredAwait,
  protected val airbrake: AirbrakeNotifier)
    extends UserExperimentCommander with Logging {

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = Future {
    db.readOnlyReplica { implicit session => generatorRepo.allActive() }
  }

  def getExperimentsByUser(userId: Id[User]): Set[ExperimentType] = {
    val staticExperiments = db.readOnlyMaster { implicit session => userExperimentRepo.getUserExperiments(userId) }
    addDynamicExperiments(userId, staticExperiments)
  }

  def addExperimentForUser(userId: Id[User], experiment: ExperimentType) = {
    db.readWrite { implicit session => userExperimentRepo.save(UserExperiment(userId = userId, experimentType = experiment)) }
  }

  def userHasExperiment(userId: Id[User], experiment: ExperimentType) = {
    getExperimentsByUser(userId).contains(experiment)
  }

  def getUserIdsByExperiment(experimentType: ExperimentType): Set[Id[User]] = {
    db.readOnlyReplica { implicit s => userExperimentRepo.getUserIdsByExperiment(experimentType) }.toSet
  }

  def internProbabilisticExperimentGenerator(
    name: Name[ProbabilisticExperimentGenerator],
    density: ProbabilityDensity[ExperimentType],
    salt: Option[String] = None,
    condition: Option[ExperimentType] = None): ProbabilisticExperimentGenerator = db.readWrite { implicit session => generatorRepo.internByName(name, density, salt, condition) }
}
