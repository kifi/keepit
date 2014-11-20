package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.auth.LegacyUserService
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ User, ExperimentType }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ SocialNetworkType, SocialId }
import play.api.mvc.Request
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import securesocial.core.Identity

import scala.concurrent.Future

@Singleton
class RemoteUserActionsHelper @Inject() (
    val airbrake: AirbrakeNotifier,
    shoebox: ShoeboxServiceClient,
    userExperimentCommander: RemoteUserExperimentCommander,
    val legacyUserService: LegacyUserService,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends UserActionsHelper with SecureSocialHelper {

  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean] = getUserExperiments(userId).map(_.contains(ExperimentType.ADMIN))

  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]] = shoebox.getUser(userId)

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = shoebox.getUserOpt(extId)

  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[ExperimentType]] = userExperimentCommander.getExperimentsByUser(userId)

  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]] = shoebox.getSocialUserInfosByUserId(userId).map(_.headOption.flatMap(_.credentials))

  def getSecureSocialIdentityFromRequest(implicit request: Request[_]): Future[Option[Identity]] = Future.successful { getSecureSocialUserFromRequest }

  def getUserIdOptFromSecureSocialIdentity(identity: Identity): Future[Option[Id[User]]] = {
    shoebox.getSocialUserInfoByNetworkAndSocialId(SocialId(identity.identityId.userId), SocialNetworkType(identity.identityId.providerId)).map { suiOpt =>
      suiOpt.flatMap(_.userId)
    }
  }
}
