package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton, Provides }
import com.keepit.common.controller.FortyTwoCookies.{ KifiInstallationCookie, ImpersonateCookie }
import com.keepit.common.db.{ ExternalId, State, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.social.{ SocialNetworkType, SocialId }

import play.api.mvc._
import securesocial.core._

import net.codingwell.scalaguice.ScalaModule
import scala.concurrent.Future

case class FakeActionAuthenticatorModule() extends ScalaModule with Logging {
  log.debug("using new FakeActionAuthenticatorModule")
  def configure(): Unit = {}

  @Singleton
  @Provides
  def actionAuthenticator(myFakeActionAuthenticator: FakeActionAuthenticator): ActionAuthenticator = myFakeActionAuthenticator

  @Singleton
  @Provides
  def fakeActionAuthenticator: FakeActionAuthenticator = new FakeActionAuthenticator

}

case class FakeIdentity(user: User) extends Identity {
  def identityId: IdentityId = IdentityId("const_fake_id", "some_network")
  def firstName: String = user.firstName
  def lastName: String = user.lastName
  def fullName: String = s"$firstName $lastName"
  def email: Option[String] = None
  def avatarUrl: Option[String] = None
  def authMethod: AuthenticationMethod = ???
  def oAuth1Info: Option[OAuth1Info] = None
  def oAuth2Info: Option[OAuth2Info] = None
  def passwordInfo: Option[PasswordInfo] = None
}

class FakeActionAuthenticator extends ActionAuthenticator with Logging {
  log.debug("using new FakeActionAuthenticator")

  var fixedUser: Option[User] = None
  var fixedExperiments: Set[ExperimentType] = Set[ExperimentType]()

  def setUser(user: User, experiments: Set[ExperimentType] = Set[ExperimentType]()): FakeActionAuthenticator = {
    fixedUser = Some(user)
    fixedExperiments = experiments
    log.debug("using fixed user: $user")
    this
  }

  private[controller] def authenticatedAction[T](apiClient: Boolean, allowPending: Boolean, bodyParser: BodyParser[T],
    onAuthenticated: AuthenticatedRequest[T] => Future[SimpleResult],
    onSocialAuthenticated: SecuredRequest[T] => Future[SimpleResult],
    onUnauthenticated: Request[T] => Future[SimpleResult]): Action[T] = Action.async(bodyParser) { request =>
    try {
      val user = fixedUser.getOrElse(User(id = Some(Id[User](1)), firstName = "Arthur", lastName = "Dent", username = None))
      log.debug("running action with fake auth of user $user, request on path ${request.path} api: $apiClient")
      val res = onAuthenticated(AuthenticatedRequest[T](FakeIdentity(user), user.id.get, user, request, fixedExperiments, None, None))
      log.debug("executed action with res: $res")
      res
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        log.error("action fail!", t)
        throw t
    }
  }

  private[controller] def isAdmin(experiments: Set[ExperimentType]) = false

  private[controller] def isAdmin(userId: Id[User]) = false
}
