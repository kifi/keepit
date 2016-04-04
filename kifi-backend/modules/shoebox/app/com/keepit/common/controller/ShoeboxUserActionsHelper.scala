package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ UserRepo, SocialUserInfoRepo, User, UserExperimentType, SocialUserInfo }
import com.keepit.social.{ UserIdentityHelper, SocialNetworkType, SocialId }
import play.api.mvc.{ RequestHeader, WrappedRequest, Request, Controller }
import securesocial.core.{ IdentityId, UserService, SecureSocial, Identity }

import scala.concurrent.Future

@Singleton
class ShoeboxUserActionsHelper @Inject() (
    db: Database,
    val airbrake: AirbrakeNotifier,
    userRepo: UserRepo,
    identityHelper: UserIdentityHelper,
    userExperimentCommander: LocalUserExperimentCommander,
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
    val kifiIdOpt = getUserIdFromSession.recover {
      case t: Throwable =>
        airbrake.notify(s"[getUserIdOpt] Caught exception $t while retrieving userId from request; cause=${t.getCause}. Path: ${request.path}, headers: ${request.headers.toMap}", t)
        None
    }.get

    kifiIdOpt match {
      case Some(userId) =>
        Future.successful(Some(userId))
      case None => getIdentityIdFromRequest match {
        case None => Future.successful(None)
        case Some(identityId) =>
          db.readOnlyMaster { implicit s => Future.successful(identityHelper.getOwnerId(identityId)) }
      }
    }
  }

  def getIdentityIdFromRequest(implicit request: RequestHeader): Option[IdentityId] = {
    try {
      val maybeAuthenticator = SecureSocial.authenticatorFromRequest
      maybeAuthenticator map { authenticator =>
        log.info(s"[getIdentityIdFromRequest] authenticator=${authenticator.id} identityId=${authenticator.identityId}")
        authenticator.identityId
      }
    } catch {
      case t: Throwable =>
        log.error(s"[getIdentityIdFromRequest] Caught exception $t; cause=${t.getCause}", t)
        None
    }
  }

}
