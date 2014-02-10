package com.keepit.commanders


import com.keepit.model.{
  ExperimentType,
  User,
  UserExperimentRepo,
  UserExperiment,
  ProbabilisticExperimentGeneratorRepo,
  ProbabilisticExperimentGenerator,
  ProbabilisticExperimentGeneratorAllCache
}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.healthcheck.AirbrakeNotifier


import com.google.inject.{Inject, Singleton}

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton //The Singleton is very importatnt here. There is a cache on the object.
class LocalUserExperimentCommander @Inject() (
    userExperimentRepo: UserExperimentRepo,
    db: Database,
    generatorRepo: ProbabilisticExperimentGeneratorRepo,
    protected val generatorCache: ProbabilisticExperimentGeneratorAllCache,
    protected val monitoredAwait: MonitoredAwait,
    protected val airbrake: AirbrakeNotifier
  )
  extends UserExperimentCommander  {

  def getExperimentGenerators() : Future[Seq[ProbabilisticExperimentGenerator]] = Future {
    db.readOnly { implicit session => generatorRepo.allActive() }
  }

  def getExperimentsByUser(userId: Id[User]): Set[ExperimentType] = {
    val staticExperiments = db.readOnly { implicit session => userExperimentRepo.getUserExperiments(userId) }
    addDynamicExperiments(userId, staticExperiments)
  }

  def addExperimentForUser(userId: Id[User], experiment: ExperimentType) = {
    db.readWrite { implicit session => userExperimentRepo.save(UserExperiment(userId = userId, experimentType = experiment)) }
  }

  def userHasExperiment(userId: Id[User], experiment: ExperimentType) = {
    getExperimentsByUser(userId).contains(experiment)
  }

}
