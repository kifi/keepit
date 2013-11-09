package com.keepit.common.controller

import com.google.inject.{Inject, Singleton, Provides}
import com.keepit.common.controller.FortyTwoCookies.{KifiInstallationCookie, ImpersonateCookie}
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.social.{SocialNetworkType, SocialId}

import play.api.mvc._
import securesocial.core._

import net.codingwell.scalaguice.ScalaModule

case class FakeActionAuthenticatorModule() extends ScalaModule {

  def configure(): Unit = {}

  @Singleton
  @Provides
  def actionAuthenticator: ActionAuthenticator = fakeActionAuthenticator

  @Singleton
  @Provides
  def fakeActionAuthenticator: FakeActionAuthenticator = new FakeActionAuthenticator
}

case class FakeIdentity(user: User) extends Identity {
  def identityId: IdentityId = ???
  def firstName: String = user.firstName
  def lastName: String = user.lastName
  def fullName: String = ???
  def email: Option[String] = None
  def avatarUrl: Option[String] = None
  def authMethod: AuthenticationMethod = ???
  def oAuth1Info: Option[OAuth1Info] = None
  def oAuth2Info: Option[OAuth2Info] = None
  def passwordInfo: Option[PasswordInfo] = None
}

class FakeActionAuthenticator @Inject() ()
  extends ActionAuthenticator with SecureSocial with Logging {

  var fixedUser: Option[User] = None
  def setUser(user: User): FakeActionAuthenticator = {
    fixedUser = Some(user)
    this
  }

  private[controller] def authenticatedAction[T](apiClient: Boolean, allowPending: Boolean, bodyParser: BodyParser[T],
    onAuthenticated: AuthenticatedRequest[T] => Result,
    onSocialAuthenticated: SecuredRequest[T] => Result,
    onUnauthenticated: Request[T] => Result): Action[T] = Action(bodyParser) { request =>
      val user = fixedUser.getOrElse(User(id = Some(Id[User](1)), firstName = "Arthur", lastName = "Dent"))
      log.info(s"running action with fake auth of user $user")
      onAuthenticated(AuthenticatedRequest[T](FakeIdentity(user), user.id.get, user, request, Set[ExperimentType](), None, None))
  }

  private[controller] def isAdmin(experiments: Set[ExperimentType]) = false

  private[controller] def isAdmin(userId: Id[User]) = false
}
