package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.controller.FortyTwoCookies.{ KifiInstallationCookie, ImpersonateCookie }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ UserRepo, ExperimentType, User }
import play.api.mvc.{ Request, Controller }

import scala.concurrent.Future

@Singleton
class ShoeboxUserActionsHelper @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    userRepo: UserRepo,
    userExperimentCommander: LocalUserExperimentCommander,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends Controller with UserActionsHelper with Logging {

  def isAdmin(userId: Id[User]): Boolean = {
    userExperimentCommander.userHasExperiment(userId, ExperimentType.ADMIN)
  }

  def getUserOpt(implicit request: Request[_]): Future[Option[User]] = Future.successful {
    for (userId <- getUserIdOpt) yield {
      db.readWrite { implicit s => userRepo.get(userId) }
    }
  }

  def getUserExperiments(implicit request: Request[_]): Future[Set[ExperimentType]] = Future.successful {
    val resOpt = for (userId <- getUserIdOpt) yield {
      userExperimentCommander.getExperimentsByUser(userId)
    }
    resOpt getOrElse Set.empty
  }

}
