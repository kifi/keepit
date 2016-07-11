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
    addDynamicExperiments(userId, staticExperiments)
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

  def getBuzzState(userId: Option[Id[User]]): Option[UserExperimentType] = {
    val experiments = userId.map { uid =>
      db.readOnlyMaster(implicit s => userExperimentRepo.getUserExperiments(uid))
    }.getOrElse(Set.empty)
    val highestPriorityExperiment = {
      import UserExperimentType._
      val DEFAULT_BUZZ_STATE = Some(ANNOUNCED_WIND_DOWN) // set this to the system-wide value
      if (experiments.contains(SYSTEM_EXPORT_ONLY)) Some(SYSTEM_EXPORT_ONLY)
      else if (experiments.contains(ANNOUNCED_WIND_DOWN)) Some(ANNOUNCED_WIND_DOWN)
      else DEFAULT_BUZZ_STATE
    }
    highestPriorityExperiment
  }
}
