package com.keepit.common.controller

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.controller.KifiSession._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model._
import play.api.Play
import play.api.mvc.Request
import securesocial.core.Identity

import scala.concurrent.Future

case class MaybeAppFakeUserActionsModule() extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[MaybeAppFakeUserActionsHelper]
  }

  @Singleton
  @Provides
  def userActionsHelper(airbrake: AirbrakeNotifier, impCookie: ImpersonateCookie, installCookie: KifiInstallationCookie) =
    new MaybeAppFakeUserActionsHelper(airbrake, impCookie, installCookie)

}

class MaybeAppFakeUserActionsHelper(
    val airbrake: AirbrakeNotifier,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends UserActionsHelper with SecureSocialHelper with Logging {

  var fixedUser: Option[User] = None
  var fixedExperiments: Set[ExperimentType] = Set[ExperimentType]()

  def setUser(user: User, experiments: Set[ExperimentType] = Set[ExperimentType]()): MaybeAppFakeUserActionsHelper = {
    fixedUser = Some(user)
    fixedExperiments = experiments
    log.info(s"[setUser] user=$user, experiments=$experiments")
    this
  }
  def removeUser(): MaybeAppFakeUserActionsHelper = {
    fixedUser = None
    fixedExperiments = Set.empty
    this
  }

  override def getUserIdOptWithFallback(implicit request: Request[_]): Future[Option[Id[User]]] = Future.successful {
    fixedUser flatMap { _.id } orElse Play.maybeApplication flatMap { _ => request.session.getUserId }
  }
  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean] = Future.successful(fixedExperiments.contains(ExperimentType.ADMIN))
  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]] = Future.successful {
    fixedUser orElse Play.maybeApplication map { _ => User(id = Some(userId), firstName = "foo", lastName = "bar", primaryUsername = Some(PrimaryUsername(Username("foo-bar"), Username("foo-bar")))) }
  }
  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = Future.successful(fixedUser)
  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[ExperimentType]] = Future.successful(fixedExperiments)
  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]] = Future.successful(None)
  def getSecureSocialIdentityFromRequest(implicit request: Request[_]): Future[Option[Identity]] = Future.successful(getSecureSocialUserFromRequest)
  def getUserIdOptFromSecureSocialIdentity(identity: Identity): Future[Option[Id[User]]] = Future.successful(fixedUser.flatMap(_.id))
}
