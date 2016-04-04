package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.view.UserSessionView
import com.keepit.model._
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.social.UserIdentityHelper
import play.api.mvc.RequestHeader

import scala.concurrent.Future

@Singleton
class ShoeboxUserActionsHelper @Inject() (
    db: Database,
    val airbrake: AirbrakeNotifier,
    userRepo: UserRepo,
    identityHelper: UserIdentityHelper,
    userExperimentCommander: LocalUserExperimentCommander,
    userSessionRepo: UserSessionRepo,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends UserActionsHelper {

  def isAdmin(userId: Id[User]): Future[Boolean] = Future.successful {
    userExperimentCommander.userHasExperiment(userId, UserExperimentType.ADMIN)
  }

  def getUserOpt(userId: Id[User]): Future[Option[User]] = Future.successful {
    db.readOnlyMaster { implicit s => Some(userRepo.get(userId)) }
  }

  def getUserExperiments(userId: Id[User]): Future[Set[UserExperimentType]] = Future.successful {
    userExperimentCommander.getExperimentsByUser(userId)
  }

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = Future.successful {
    db.readOnlyMaster { implicit s => Some(userRepo.get(extId)) }
  }

  def getUserIdOptWithFallback(implicit request: RequestHeader): Future[Option[Id[User]]] = {
    getUserIdFromSession(request).toOption.flatten match {
      case Some(userId) =>
        Future.successful(Some(userId))
      case None =>
        getSessionIdFromRequest(request).map { sessionId =>
          db.readOnlyMaster { implicit session => //using cache
            Future.successful(userSessionRepo.getOpt(ExternalId[UserSession](sessionId.id)).flatMap(_.userId))
          }
        }.getOrElse(Future.successful(None))
    }
  }

  def getSessionByExternalId(sessionId: UserSessionExternalId): Future[Option[UserSessionView]] = {
    db.readOnlyMaster { implicit session => //using cache
      Future.successful(userSessionRepo.getOpt(ExternalId[UserSession](sessionId.id)).map(_.toUserSessionView))
    }
  }

}
