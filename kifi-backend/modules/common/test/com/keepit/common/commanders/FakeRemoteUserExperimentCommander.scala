package com.keepit.common.commanders

import com.google.inject.{ Singleton, Provides }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.db.Id
import com.keepit.model.{ ExperimentType, User, ProbabilisticExperimentGenerator }
import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.Future

class FakeRemoteUserExperimentCommander extends RemoteUserExperimentCommander(null, null, null, null) {
  override def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = Future.successful(Nil)

  override def getExperimentsByUser(userId: Id[User]): Future[Set[ExperimentType]] = Future.successful(Set())

  override def getUsersByExperiment(experiment: ExperimentType): Future[Set[User]] = Future.successful(Set())
}

case class FakeRemoteUserExperimentModule() extends ScalaModule {
  def configure() {}

  @Singleton
  @Provides
  def experimentCmdr: RemoteUserExperimentCommander = new FakeRemoteUserExperimentCommander()
}
