package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.auth.LegacyUserService
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ UserRepo, SocialUserInfoRepo, User, ExperimentType, SocialUserInfo }
import com.keepit.social.{ SocialNetworkType, SocialId }
import play.api.mvc.{ WrappedRequest, Request, Controller }
import securesocial.core.{ UserService, SecureSocial, Identity }

import scala.concurrent.Future

@Singleton
class ShoeboxUserActionsHelper @Inject() (
    db: Database,
    val airbrake: AirbrakeNotifier,
    val legacyUserService: LegacyUserService,
    userRepo: UserRepo,
    suiRepo: SocialUserInfoRepo,
    userExperimentCommander: LocalUserExperimentCommander,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends Controller with UserActionsHelper with SecureSocialHelper with Logging {

  override def buildNonUserRequest[A](implicit request: Request[A]): NonUserRequest[A] = NonUserRequest[A](request, () => getSecureSocialUserFromRequest)

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

  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]] = Future.successful {
    db.readOnlyMaster { implicit s => suiRepo.getByUser(userId).headOption.flatMap(_.credentials) }
  }

  def getSecureSocialIdentityFromRequest(implicit request: Request[_]): Future[Option[Identity]] = Future.successful(getSecureSocialUserFromRequest)

  def getUserIdOptFromSecureSocialIdentity(identity: Identity): Future[Option[Id[User]]] = Future.successful {
    val socialUser = db.readOnlyMaster { implicit s =>
      suiRepo.get(SocialId(identity.identityId.userId), SocialNetworkType(identity.identityId.providerId))
    }
    socialUser.userId
  }

}
