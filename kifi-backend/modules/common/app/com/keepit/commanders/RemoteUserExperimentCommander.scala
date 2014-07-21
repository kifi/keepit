package com.keepit.commanders

import com.keepit.model.{ ExperimentType, User, ProbabilisticExperimentGenerator, ProbabilisticExperimentGeneratorAllCache }
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.google.inject.{ Inject, Singleton }

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton //The Singleton is very importatnt here. There is a cache on the object.
class RemoteUserExperimentCommander @Inject() (
    shoebox: ShoeboxServiceClient,
    protected val generatorCache: ProbabilisticExperimentGeneratorAllCache,
    protected val monitoredAwait: MonitoredAwait,
    protected val airbrake: AirbrakeNotifier) extends UserExperimentCommander {

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = shoebox.getExperimentGenerators()

  def getExperimentsByUser(userId: Id[User]): Future[Set[ExperimentType]] = {
    shoebox.getUserExperiments(userId).map { experimentSeq => addDynamicExperiments(userId, experimentSeq.toSet) }
  }

}

