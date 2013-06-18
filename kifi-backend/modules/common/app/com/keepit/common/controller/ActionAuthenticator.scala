package com.keepit.common.controller

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social._
import com.keepit.model._
import play.api.i18n.Messages
import play.api.libs.json.JsNumber
import play.api.mvc._
import securesocial.core._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.duration._
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Promise
import scala.concurrent.Future

case class ReportedException(val id: ExternalId[HealthcheckError], val cause: Throwable) extends Exception(id.toString, cause)

case class AuthenticatedRequest[T](
    identity: Identity,
    userId: Id[User],
    user: User,
    request: Request[T],
    experiments: Set[State[ExperimentType]] = Set(),
    kifiInstallationId: Option[ExternalId[KifiInstallation]] = None,
    adminUserId: Option[Id[User]] = None)
  extends WrappedRequest(request)

object ActionAuthenticator {
  val FORTYTWO_USER_ID = "fortytwo_user_id"
}

trait ActionAuthenticator extends SecureSocial {
  private[controller] def isAdmin(userId: Id[User]): Boolean
  private[controller] def authenticatedAction[T](
      apiClient: Boolean,
      allowPending: Boolean,
      bodyParser: BodyParser[T],
      onAuthenticated: AuthenticatedRequest[T] => Result,
      onUnauthenticated: Request[T] => Result): Action[T]

  private[controller] def authenticatedAction[T](
      apiClient: Boolean,
      allowPending: Boolean,
      bodyParser: BodyParser[T],
      onAuthenticated: AuthenticatedRequest[T] => Result): Action[T] =
        authenticatedAction(
          apiClient = apiClient,
          allowPending = allowPending,
          bodyParser = bodyParser,
          onAuthenticated = onAuthenticated,
          onUnauthenticated = { implicit request =>
            if (apiClient) {
              Forbidden(JsNumber(0))
            } else {
              Redirect("/login")
                .flashing("error" -> Messages("securesocial.loginRequired"))
                .withSession(session + (SecureSocial.OriginalUrlKey -> request.uri))
            }
          })
}

@Singleton
class RemoteActionAuthenticator @Inject() (
  fortyTwoServices: FortyTwoServices,
  healthcheckPlugin: HealthcheckPlugin,
  impersonateCookie: ImpersonateCookie,
  kifiInstallationCookie: KifiInstallationCookie,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait)
    extends ActionAuthenticator with SecureSocial with Logging {

  import scala.concurrent.ExecutionContext.Implicits.global

  private def getExperiments(userId: Id[User]): Future[Seq[State[ExperimentType]]] = shoeboxClient.getUserExperiments(userId)

  private def authenticatedHandler[T](apiClient: Boolean, allowPending: Boolean)(authAction: AuthenticatedRequest[T] => Result) = { implicit request: SecuredRequest[T] => /* onAuthenticated */
      val userId = request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map(id => Id[User](id.toLong)).getOrElse {
        // If this fails, then SecureSocial says they're authenticated, but they're not.
        monitoredAwait.result(shoeboxClient.getSocialUserInfoByNetworkAndSocialId(SocialId(request.user.id.id), SocialNetworkType(request.user.id.providerId)), 3 seconds, s"on social user id $request.user.id.id").get.userId.get
      }
      val experimentsFuture = getExperiments(userId).map(_.toSet)
      val impersonatedUserIdOpt: Option[ExternalId[User]] = impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
      val kifiInstallationId: Option[ExternalId[KifiInstallation]] = kifiInstallationCookie.decodeFromCookie(request.cookies.get(kifiInstallationCookie.COOKIE_NAME))
      val socialUser = request.user
      val newSession = session + (ActionAuthenticator.FORTYTWO_USER_ID -> userId.toString)
      impersonatedUserIdOpt match {
        case Some(impExternalUserId) =>
          val impUserId = shoeboxClient.getUserIdsByExternalIds(Seq(impExternalUserId)).map(_.head)
          val experiments = monitoredAwait.result(experimentsFuture, 3 second, s"on user id $userId", Set[State[ExperimentType]]())
          if (!experiments.contains(ExperimentTypes.ADMIN)) throw new IllegalStateException("non admin user %s tries to impersonate to %s".format(userId, impUserId))
          val impSocialUserInfoFuture = shoeboxClient.getSocialUserInfosByUserId(userId)

          val impSocialUserInfo = monitoredAwait.result(impSocialUserInfoFuture, 3 seconds, s"on user id $userId")
          log.info("[IMPERSONATOR] admin user %s is impersonating user %s with request %s".format(userId, impSocialUserInfo, request.request.path))
          executeAction(authAction, monitoredAwait.result(impUserId, 3 seconds, s"on impersonating external user id $impExternalUserId"),
            impSocialUserInfo.head.credentials.get, experiments.toSet, kifiInstallationId, newSession, request.request, Some(userId), allowPending)
        case None =>
          executeAction(authAction, userId, socialUser, monitoredAwait.result(experimentsFuture, 3 second, s"on experiments for user $userId", Set[State[ExperimentType]]()), kifiInstallationId, newSession, request.request, None, allowPending)
      }
    }

  private[controller] def authenticatedAction[T](
      apiClient: Boolean,
      allowPending: Boolean,
      bodyParser: BodyParser[T],
      onAuthenticated: AuthenticatedRequest[T] => Result,
      onUnauthenticated: Request[T] => Result): Action[T] = UserAwareAction(bodyParser) { request =>
    val result = request.user match {
      case Some(user) =>
        authenticatedHandler(apiClient, allowPending)(onAuthenticated)(SecuredRequest(user, request))
      case None =>
        onUnauthenticated(request)
    }
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com")
    }.map { h =>
      result.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true"
      )
    }.getOrElse(result)
  }

  private[controller] def isAdmin(experiments: Set[State[ExperimentType]]) = experiments.contains(ExperimentTypes.ADMIN)

  private[controller] def isAdmin(userId: Id[User]) = monitoredAwait.result(
    getExperiments(userId).map(r => r.contains(ExperimentTypes.ADMIN)), 2 seconds, s"on is admin experiment for user $userId", false)

  private def executeAction[T](action: AuthenticatedRequest[T] => Result, userId: Id[User], identity: Identity,
      experiments: Set[State[ExperimentType]], kifiInstallationId: Option[ExternalId[KifiInstallation]],
      newSession: Session, request: Request[T], adminUserId: Option[Id[User]] = None, allowPending: Boolean) = {
    val user = shoeboxClient.getUsers(Seq(userId)).map(_.head)
    val cleanedSesison = newSession - IdentityProvider.SessionId + ("server_version" -> fortyTwoServices.currentVersion.value)
    log.debug("sending response with new session [%s] of user id: %s".format(cleanedSesison, userId))
    try {
      action(AuthenticatedRequest[T](identity, userId, monitoredAwait.result(user, 3 seconds, s"getting user $userId"), request, experiments, kifiInstallationId, adminUserId)) match {
        case r: PlainResult => r.withSession(newSession)
        case any: Result => any
      }
    } catch {
      case e: Throwable =>
        val globalError = healthcheckPlugin.addError(HealthcheckError(
            error = Some(e),
            method = Some(request.method.toUpperCase()),
            path = Some(request.path),
            callType = Healthcheck.API,
            errorMessage = Some("Error executing with userId %s, experiments [%s], installation %s".format(
                userId, experiments.mkString(","), kifiInstallationId.getOrElse("NA")))))
        log.error("healthcheck reported [%s]".format(globalError.id), e)
        throw ReportedException(globalError.id, e)
    }
  }
}
