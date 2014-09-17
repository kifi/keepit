package com.keepit.common.controller

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.{ User, ExperimentType }
import play.api.mvc.Request

import scala.concurrent.Future

case class FakeUserActionsModule() extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[FakeUserActionsHelper]
  }
}

@Singleton
class FakeUserActionsHelper() extends UserActionsHelper with Logging {

  var fixedUser: Option[User] = None
  var fixedExperiments: Set[ExperimentType] = Set[ExperimentType]()

  def setUser(user: User, experiments: Set[ExperimentType] = Set[ExperimentType]()): FakeUserActionsHelper = {
    fixedUser = Some(user)
    fixedExperiments = experiments
    log.info(s"[setUser] user=$user, experiments=$experiments")
    this
  }

  override def getUserIdOpt(implicit request: Request[_]): Option[Id[User]] = {
    val res = fixedUser.flatMap(_.id)
    println(s"res=$res")
    res
  }
  def getUserOpt(implicit request: Request[_]): Future[Option[User]] = Future.successful(fixedUser)
  def isAdmin(userId: Id[User]): Boolean = fixedExperiments.contains(ExperimentType.ADMIN)
  def getUserExperiments(implicit request: Request[_]): Future[Set[ExperimentType]] = Future.successful(fixedExperiments)

}
