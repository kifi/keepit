package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ User, ExperimentType }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.mvc.Request
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class RemoteUserActionsHelper @Inject() (
    airbrake: AirbrakeNotifier,
    shoebox: ShoeboxServiceClient,
    userExperimentCommander: RemoteUserExperimentCommander,
    val kifiInstallationCookie: KifiInstallationCookie) extends UserActionsHelper {

  def getUserOpt(implicit request: Request[_]): Future[Option[User]] = ???

  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean] = getUserExperiments.map(_.contains(ExperimentType.ADMIN))

  def getUserExperiments(implicit request: Request[_]): Future[Set[ExperimentType]] = {
    getUserIdOpt map { userId =>
      userExperimentCommander.getExperimentsByUser(userId)
    } getOrElse Future.successful(Set.empty)
  }

}
