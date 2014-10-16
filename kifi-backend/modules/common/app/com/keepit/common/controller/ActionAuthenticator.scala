package com.keepit.common.controller

import scala.concurrent.Future
import scala.concurrent.duration._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ SocialNetworkType, SocialId }
import com.keepit.commanders.RemoteUserExperimentCommander
import play.api.mvc._
import securesocial.core._

case class ReportedException(id: ExternalId[AirbrakeError], cause: Throwable) extends Exception(id.toString, cause)

case class AuthenticatedRequest[T](
  identity: Identity,
  userId: Id[User],
  user: User,
  request: Request[T],
  experiments: Set[ExperimentType] = Set(),
  kifiInstallationId: Option[ExternalId[KifiInstallation]] = None,
  adminUserId: Option[Id[User]] = None)
    extends WrappedRequest(request)

object ActionAuthenticator {
  val FORTYTWO_USER_ID = "fortytwo_user_id"
}

@deprecated("See UserActions", "oct 2014")
trait ActionAuthenticator extends SecureSocial {
  private[controller] def isAdmin(userId: Id[User]): Boolean
  private[controller] def authenticatedAction[T](
    apiClient: Boolean,
    allowPending: Boolean,
    bodyParser: BodyParser[T],
    onAuthenticated: AuthenticatedRequest[T] => Future[Result],
    onSocialAuthenticated: SecuredRequest[T] => Future[Result],
    onUnauthenticated: Request[T] => Future[Result]): Action[T]

  def getSecureSocialUser[A](implicit request: Request[A]): Option[Identity] = {
    for (
      authenticator <- SecureSocial.authenticatorFromRequest;
      user <- UserService.find(authenticator.identityId)
    ) yield {
      user
    }
  }

  object SecureSocialUserAwareAction extends ActionBuilder[RequestWithUser] {
    def invokeBlock[A](request: Request[A], block: (RequestWithUser[A]) => Future[Result]): Future[Result] = {
      block(RequestWithUser(getSecureSocialUser(request), request))
    }
  }

}

