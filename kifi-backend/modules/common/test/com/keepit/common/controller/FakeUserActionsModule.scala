package com.keepit.common.controller

import com.google.inject.{ Provides, Inject, Singleton }
import com.keepit.common.controller.FortyTwoCookies.{ KifiInstallationCookie, ImpersonateCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ SocialUserInfo, User, UserExperimentType }
import play.api.mvc.Request
import securesocial.core.{ IdentityId, Identity }

import scala.concurrent.Future

case class FakeUserActionsModule() extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[FakeUserActionsHelper]
  }

  @Singleton
  @Provides
  def userActionsHelper(airbrake: AirbrakeNotifier, impCookie: ImpersonateCookie, installCookie: KifiInstallationCookie) = new FakeUserActionsHelper(airbrake, impCookie, installCookie)

}

class FakeUserActionsHelper(
    val airbrake: AirbrakeNotifier,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends UserActionsHelper with Logging {

  var fixedUser: Option[User] = None
  var fixedExperiments: Set[UserExperimentType] = Set[UserExperimentType]()

  def setUser(user: User, experiments: Set[UserExperimentType] = Set[UserExperimentType]()): FakeUserActionsHelper = {
    fixedUser = Some(user)
    fixedExperiments = experiments
    log.info(s"[setUser] user=$user, experiments=$experiments")
    this
  }

  def unsetUser(): Unit = {
    fixedUser = None
    fixedExperiments = Set()
  }

  override def getUserIdOptWithFallback(implicit request: Request[_]): Future[Option[Id[User]]] = Future.successful(fixedUser.flatMap(_.id))
  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean] = Future.successful(fixedExperiments.contains(UserExperimentType.ADMIN))
  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]] = Future.successful(fixedUser)
  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = Future.successful(fixedUser)
  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[UserExperimentType]] = Future.successful(fixedExperiments)
  def getUserIdOptFromSecureSocialIdentity(identityId: IdentityId): Future[Option[Id[User]]] = Future.successful(fixedUser.flatMap(_.id))
  def getIdentityIdFromRequest(implicit request: Request[_]): Option[IdentityId] = None
}
