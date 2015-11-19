package com.keepit.commanders

import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.google.inject.{ Inject, Singleton }

import scala.concurrent.{ ExecutionContext, Future }

import com.keepit.common.math.ProbabilityDensity
import com.keepit.common.logging.Logging

@Singleton //The Singleton is very importatnt here. There is a cache on the object.
class LocalUserExperimentCommander @Inject() (
  userExperimentRepo: UserExperimentRepo,
  db: Database,
  generatorRepo: ProbabilisticExperimentGeneratorRepo,
  implicit val defaultContext: ExecutionContext,
  protected val generatorCache: ProbabilisticExperimentGeneratorAllCache,
  protected val monitoredAwait: MonitoredAwait,
  protected val airbrake: AirbrakeNotifier)
    extends UserExperimentCommander with Logging {

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = Future {
    db.readOnlyReplica { implicit session => generatorRepo.allActive() }
  }

  def getExperimentsByUser(userId: Id[User]): Set[UserExperimentType] = {
    val staticExperiments = db.readOnlyMaster { implicit session => userExperimentRepo.getUserExperiments(userId) }
    addDynamicExperiments(userId, staticExperiments) + UserExperimentType.CREATE_TEAM // Slap Andrew around with a large trout if this is here for, like, a while.
  }

  def addExperimentForUser(userId: Id[User], experiment: UserExperimentType): UserExperiment = {
    db.readWrite(attempts = 3) { implicit session =>
      userExperimentRepo.get(userId, experiment, excludeState = None) match {
        case None => userExperimentRepo.save(UserExperiment(userId = userId, experimentType = experiment))
        case Some(existing) if !existing.isActive => userExperimentRepo.save(existing.copy(state = UserExperimentStates.ACTIVE))
        case Some(existing) => existing
      }

    }
  }

  def userHasExperiment(userId: Id[User], experiment: UserExperimentType) = {
    getExperimentsByUser(userId).contains(experiment)
  }

  def getUserIdsByExperiment(experimentType: UserExperimentType): Set[Id[User]] = {
    db.readOnlyReplica { implicit s => userExperimentRepo.getUserIdsByExperiment(experimentType) }.toSet
  }

  def internProbabilisticExperimentGenerator(
    name: Name[ProbabilisticExperimentGenerator],
    density: ProbabilityDensity[UserExperimentType],
    salt: Option[String] = None,
    condition: Option[UserExperimentType] = None): ProbabilisticExperimentGenerator = db.readWrite { implicit session => generatorRepo.internByName(name, density, salt, condition) }
}
