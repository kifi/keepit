package com.keepit.common.controller

import scala.concurrent.Future
import scala.concurrent.duration._

import com.google.inject.{Inject, Singleton}
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}
import com.keepit.common.db._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{SocialNetworkType, SocialId}

import play.api.i18n.Messages
import play.api.libs.json.JsNumber
import play.api.mvc._
import securesocial.core._

case class ReportedException(val id: ExternalId[AirbrakeError], val cause: Throwable) extends Exception(id.toString, cause)

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

  implicit class MaybeAuthenticatedRequest(val request: Request[_]) extends AnyVal {
    def identityOpt: Option[Identity] = request match {
      case ar: AuthenticatedRequest[_] => Some(ar.identity)
      case sr: SecuredRequest[_] => Some(sr.user)
      case _ => None
    }
    def userIdOpt: Option[Id[User]] = request match {
      case ar: AuthenticatedRequest[_] => Some(ar.userId)
      case _ => None
    }
    def userOpt: Option[User] = request match {
      case ar: AuthenticatedRequest[_] => Some(ar.user)
      case _ => None
    }
  }
}

trait ActionAuthenticator extends SecureSocial {
  private[controller] def isAdmin(userId: Id[User]): Boolean
  private[controller] def authenticatedAction[T](
      apiClient: Boolean,
      allowPending: Boolean,
      bodyParser: BodyParser[T],
      onAuthenticated: AuthenticatedRequest[T] => Result,
      onSocialAuthenticated: SecuredRequest[T] => Result,
      onUnauthenticated: Request[T] => Result): Action[T]

  private[controller] def authenticatedAction[T](
      apiClient: Boolean,
      allowPending: Boolean,
      bodyParser: BodyParser[T],
      onAuthenticated: AuthenticatedRequest[T] => Result,
      onUnauthenticated: Request[T] => Result): Action[T] = {
    authenticatedAction(
      apiClient = apiClient,
      allowPending = allowPending,
      bodyParser = bodyParser,
      onAuthenticated = onAuthenticated,
      onSocialAuthenticated = onUnauthenticated,
      onUnauthenticated = onUnauthenticated)
  }

  private[controller] def authenticatedAction[T](
      apiClient: Boolean,
      allowPending: Boolean,
      bodyParser: BodyParser[T],
      onAuthenticated: AuthenticatedRequest[T] => Result): Action[T] = {
        val onUnauthenticated = { implicit request: Request[_] =>
          if (apiClient) {
            Forbidden(JsNumber(0))
          } else {
            Redirect("/login")
                .flashing("error" -> Messages("securesocial.loginRequired"))
                .withSession(session + (SecureSocial.OriginalUrlKey -> request.uri))
          }
        }
        authenticatedAction(
          apiClient = apiClient,
          allowPending = allowPending,
          bodyParser = bodyParser,
          onAuthenticated = onAuthenticated,
          onSocialAuthenticated = onUnauthenticated,
          onUnauthenticated = onUnauthenticated)
  }
}

@Singleton
class RemoteActionAuthenticator @Inject() (
  fortyTwoServices: FortyTwoServices,
  airbrake: AirbrakeNotifier,
  impersonateCookie: ImpersonateCookie,
  kifiInstallationCookie: KifiInstallationCookie,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait)
    extends ActionAuthenticator with SecureSocial with Logging {

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  private def getExperiments(userId: Id[User]): Future[Seq[ExperimentType]] = shoeboxClient.getUserExperiments(userId)

  private def authenticatedHandler[T](userId: Id[User], apiClient: Boolean, allowPending: Boolean)(authAction: AuthenticatedRequest[T] => Result) = { implicit request: SecuredRequest[T] => /* onAuthenticated */
      val experimentsFuture = getExperiments(userId).map(_.toSet)
      val impersonatedUserIdOpt: Option[ExternalId[User]] = impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
      val kifiInstallationId: Option[ExternalId[KifiInstallation]] = kifiInstallationCookie.decodeFromCookie(request.cookies.get(kifiInstallationCookie.COOKIE_NAME))
      val socialUser = request.user
      val newSession = session + (ActionAuthenticator.FORTYTWO_USER_ID -> userId.toString)
      impersonatedUserIdOpt match {
        case Some(impExternalUserId) =>
          val impUserId = shoeboxClient.getUserIdsByExternalIds(Seq(impExternalUserId)).map(_.head)
          val experiments = monitoredAwait.result(experimentsFuture, 3 second, s"on user id $userId", Set[ExperimentType]())
          if (!experiments.contains(ExperimentType.ADMIN)) throw new IllegalStateException(s"non admin user $userId tries to impersonate to $impUserId")
          val impSocialUserInfoFuture = shoeboxClient.getSocialUserInfosByUserId(userId)

          val impSocialUserInfo = monitoredAwait.result(impSocialUserInfoFuture, 3 seconds, s"on user id $userId")
          log.info(s"[IMPERSONATOR] admin user $userId is impersonating user $impSocialUserInfo with request ${request.request.path}")
          executeAction(authAction, monitoredAwait.result(impUserId, 3 seconds, s"on impersonating external user id $impExternalUserId"),
            impSocialUserInfo.head.credentials.get, experiments.toSet, kifiInstallationId, newSession, request.request, Some(userId), allowPending)
        case None =>
          executeAction(authAction, userId, socialUser, monitoredAwait.result(experimentsFuture, 3 second, s"on experiments for user $userId", Set[ExperimentType]()), kifiInstallationId, newSession, request.request, None, allowPending)
      }
    }

  private[controller] def authenticatedAction[T](
      apiClient: Boolean,
      allowPending: Boolean,
      bodyParser: BodyParser[T],
      onAuthenticated: AuthenticatedRequest[T] => Result,
      onSocialAuthenticated: SecuredRequest[T] => Result,
      onUnauthenticated: Request[T] => Result): Action[T] = UserAwareAction(bodyParser) { request =>
    val result = request.user match {
      case Some(socialUser) =>
        val uidOpt = request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map(id => Id[User](id.toLong)).orElse {
          monitoredAwait.result(shoeboxClient.getSocialUserInfoByNetworkAndSocialId(
            SocialId(socialUser.identityId.userId), SocialNetworkType(socialUser.identityId.providerId)),
            3 seconds, s"on social user id $request.user.id.id").flatMap(_.userId)
        }
        uidOpt match {
          case Some(userId) =>
            authenticatedHandler(userId, apiClient, allowPending)(onAuthenticated)(SecuredRequest(socialUser, request))
          case None =>
            onSocialAuthenticated(SecuredRequest(socialUser, request))
        }
      case None =>
        onUnauthenticated(request)
    }
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com") || host.endsWith("browserstack.com")
    }.map { h =>
      result.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true"
      )
    }.getOrElse(result)
  }

  private[controller] def isAdmin(experiments: Set[ExperimentType]) = experiments.contains(ExperimentType.ADMIN)

  private[controller] def isAdmin(userId: Id[User]) = monitoredAwait.result(
    getExperiments(userId).map(r => r.contains(ExperimentType.ADMIN)), 2 seconds, s"on is admin experiment for user $userId", false)

  private def executeAction[T](action: AuthenticatedRequest[T] => Result, userId: Id[User], identity: Identity,
      experiments: Set[ExperimentType], kifiInstallationId: Option[ExternalId[KifiInstallation]],
      newSession: Session, request: Request[T], adminUserId: Option[Id[User]] = None, allowPending: Boolean) = {
    val user = shoeboxClient.getUsers(Seq(userId)).map(_.head)
    try {
      action(AuthenticatedRequest[T](identity, userId, monitoredAwait.result(user, 3 seconds, s"getting user $userId"), request, experiments, kifiInstallationId, adminUserId)) match {
        case r: PlainResult => r.withSession(newSession)
        case any: Result => any
      }
    } catch {
      case e: Throwable =>
        val globalError = airbrake.notify(AirbrakeError.incoming(request, e,
            s"Error executing with userId $userId, experiments [${experiments.mkString(",")}], installation ${kifiInstallationId.getOrElse("NA")}"))
        log.error(s"error reported [${globalError.id}]", e)
        throw ReportedException(globalError.id, e)
    }
  }
}
