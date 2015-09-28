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
import play.api.mvc.{ WrappedRequest, Request, Controller }
import securesocial.core.{ UserService, SecureSocial, Identity }

import scala.concurrent.Future

@Singleton
class ShoeboxUserActionsHelper @Inject() (
    db: Database,
    val airbrake: AirbrakeNotifier,
    userRepo: UserRepo,
    identityHelper: UserIdentityHelper,
    userExperimentCommander: LocalUserExperimentCommander,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends Controller with UserActionsHelper with SecureSocialHelper with Logging {

  override def buildNonUserRequest[A](implicit request: Request[A]): NonUserRequest[A] = NonUserRequest[A](request, () => getSecureSocialUserFromRequest)

  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean] = Future.successful {
    userExperimentCommander.userHasExperiment(userId, UserExperimentType.ADMIN)
  }

  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]] = Future.successful {
    db.readOnlyMaster { implicit s => Some(userRepo.get(userId)) }
  }

  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[UserExperimentType]] = Future.successful {
    userExperimentCommander.getExperimentsByUser(userId)
  }

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = Future.successful {
    db.readOnlyMaster { implicit s => Some(userRepo.get(extId)) }
  }

  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]] = Future.successful {
    db.readOnlyMaster { implicit s => identityHelper.getUserIdentity(userId) }
  }

  def getSecureSocialIdentityFromRequest(implicit request: Request[_]): Future[Option[Identity]] = Future.successful(getSecureSocialUserFromRequest)

  def getUserIdOptFromSecureSocialIdentity(identity: Identity): Future[Option[Id[User]]] = Future.successful {
    db.readOnlyMaster { implicit s => identityHelper.getOwnerId(identity.identityId) }
  }

}
