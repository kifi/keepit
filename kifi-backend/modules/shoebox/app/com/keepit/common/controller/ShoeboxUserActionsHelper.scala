package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ UserRepo, SocialUserInfoRepo, User, ExperimentType, SocialUserInfo }
import play.api.mvc.{ WrappedRequest, Request, Controller }
import securesocial.core.{ UserService, SecureSocial, Identity }

import scala.concurrent.Future

trait ShoeboxSecureSocialHelper {
  def getSecureSocialUserFromRequest[A](implicit request: Request[A]): Option[Identity] = {
    for (
      authenticator <- SecureSocial.authenticatorFromRequest;
      user <- UserService.find(authenticator.identityId)
    ) yield {
      user
    }
  }
}

@Singleton
class ShoeboxUserActionsHelper @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    userRepo: UserRepo,
    suiRepo: SocialUserInfoRepo,
    userExperimentCommander: LocalUserExperimentCommander,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends Controller with UserActionsHelper with ShoeboxSecureSocialHelper with Logging {

  override def buildNonUserRequest[A](implicit request: Request[A]): NonUserRequest[A] = NonUserRequest[A](request, getSecureSocialUserFromRequest)

  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean] = Future.successful {
    userExperimentCommander.userHasExperiment(userId, ExperimentType.ADMIN)
  }

  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]] = Future.successful {
    db.readOnlyMaster { implicit s => Some(userRepo.get(userId)) }
  }

  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[ExperimentType]] = Future.successful {
    userExperimentCommander.getExperimentsByUser(userId)
  }

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = Future.successful {
    db.readOnlyMaster { implicit s => Some(userRepo.get(extId)) }
  }

  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]] = Future.successful(getSecureSocialUserFromRequest)
}
