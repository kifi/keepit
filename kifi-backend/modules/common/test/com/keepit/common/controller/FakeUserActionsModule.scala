package com.keepit.common.controller

import com.google.inject.{ Provides, Inject, Singleton }
import com.keepit.common.controller.FortyTwoCookies.{ KifiInstallationCookie, ImpersonateCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ SocialUserInfo, User, ExperimentType }
import play.api.mvc.Request
import securesocial.core.Identity

import scala.concurrent.Future

case class FakeUserActionsModule() extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[FakeUserActionsHelper]
  }

  @Singleton
  @Provides
  def userActionsHelper(impCookie: ImpersonateCookie, installCookie: KifiInstallationCookie) = new FakeUserActionsHelper(impCookie, installCookie)

}

class FakeUserActionsHelper(
    val airbrake: AirbrakeNotifier,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends UserActionsHelper with SecureSocialHelper with Logging {

  var fixedUser: Option[User] = None
  var fixedExperiments: Set[ExperimentType] = Set[ExperimentType]()

  def setUser(user: User, experiments: Set[ExperimentType] = Set[ExperimentType]()): FakeUserActionsHelper = {
    fixedUser = Some(user)
    fixedExperiments = experiments
    log.info(s"[setUser] user=$user, experiments=$experiments")
    this
  }

  override def getUserIdOpt(implicit request: Request[_]): Future[Option[Id[User]]] = Future.successful(fixedUser.flatMap(_.id))
  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean] = Future.successful(fixedExperiments.contains(ExperimentType.ADMIN))
  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]] = Future.successful(fixedUser)
  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = Future.successful(fixedUser)
  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[ExperimentType]] = Future.successful(fixedExperiments)
  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]] = Future.successful(None)
  def getSecureSocialIdentityFromRequest(implicit request: Request[_]): Future[Option[Identity]] = Future.successful(getSecureSocialUserFromRequest)
  def getUserIdOptFromSecureSocialIdentity(identity: Identity): Future[Option[Id[User]]] = Future.successful(fixedUser.flatMap(_.id))
}
