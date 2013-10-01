package com.keepit.common.controller


import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.FortyTwoCookies.{KifiInstallationCookie, ImpersonateCookie}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.social.{SocialNetworkType, SocialId}

import play.api.mvc._
import securesocial.core._

@Singleton
class ShoeboxActionAuthenticator @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userExperimentRepo: UserExperimentRepo,
  userRepo: UserRepo,
  fortyTwoServices: FortyTwoServices,
  airbrake: AirbrakeNotifier,
  impersonateCookie: ImpersonateCookie,
  kifiInstallationCookie: KifiInstallationCookie)
  extends ActionAuthenticator with SecureSocial with Logging {

  private def loadUserId(userIdOpt: Option[Id[User]], socialId: SocialId,
                         socialNetworkType: SocialNetworkType)(implicit session: RSession): Option[Id[User]] = {
    userIdOpt match {
      case None =>
        val socialUser = socialUserInfoRepo.get(socialId, socialNetworkType)
        socialUser.userId
      case Some(userId) =>
        val socialUser = socialUserInfoRepo.get(socialId, socialNetworkType)
        if (socialUser.userId.get != userId) {
          log.error(s"Social user id $socialUser does not match session user id $userId")
        }
        Some(userId)
    }
  }

  private def getExperiments(userId: Id[User])(implicit session: RSession): Set[State[ExperimentType]] = userExperimentRepo.getUserExperiments(userId)

  private def authenticatedHandler[T](userId: Id[User], apiClient: Boolean, allowPending: Boolean)(authAction: AuthenticatedRequest[T] => Result) = { implicit request: SecuredRequest[T] => /* onAuthenticated */
    val socialUser = request.user
    val impersonatedUserIdOpt: Option[ExternalId[User]] = impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
    val kifiInstallationId: Option[ExternalId[KifiInstallation]] = kifiInstallationCookie.decodeFromCookie(request.cookies.get(kifiInstallationCookie.COOKIE_NAME))
    val experiments = db.readOnly { implicit s => getExperiments(userId) }
    val newSession =
      if (session.get(ActionAuthenticator.FORTYTWO_USER_ID) == Some(userId.toString)) None
      else Some(session + (ActionAuthenticator.FORTYTWO_USER_ID -> userId.toString))
    impersonatedUserIdOpt match {
      case Some(impExternalUserId) =>
        val (impExperiments, impSocialUser, impUserId) = db.readOnly { implicit session =>
          val impUserId = userRepo.get(impExternalUserId).id.get
          if (!isAdmin(experiments)) throw new IllegalStateException("non admin user %s tries to impersonate to %s".format(userId, impUserId))
          val impSocialUserInfo = socialUserInfoRepo.getByUser(impUserId).head
          (getExperiments(impUserId), impSocialUserInfo.credentials.get, impUserId)
        }
        log.info("[IMPERSONATOR] admin user %s is impersonating user %s with request %s".format(userId, impSocialUser, request.request.path))
        executeAction(authAction, impUserId, impSocialUser, impExperiments, kifiInstallationId, newSession, request.request, Some(userId), allowPending)
      case None =>
        executeAction(authAction, userId, socialUser, experiments, kifiInstallationId, newSession, request.request, None, allowPending)
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
      case Some(identity) =>
        val userIdOpt = request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map{id => Id[User](id.toLong)}
        val uidOpt = db.readOnly { implicit s =>
          loadUserId(userIdOpt, SocialId(identity.identityId.userId), SocialNetworkType(identity.identityId.providerId))
        }
        uidOpt match {
          case Some(userId) =>
            authenticatedHandler(userId, apiClient, allowPending)(onAuthenticated)(SecuredRequest(identity, request))
          case None =>
            onSocialAuthenticated(SecuredRequest(identity, request))
        }
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

  private[controller] def isAdmin(userId: Id[User]) = db.readOnly { implicit session =>
    userExperimentRepo.hasExperiment(userId, ExperimentTypes.ADMIN)
  }

  private def executeAction[T](action: AuthenticatedRequest[T] => Result, userId: Id[User], identity: Identity,
     experiments: Set[State[ExperimentType]], kifiInstallationId: Option[ExternalId[KifiInstallation]],
     newSession: Option[Session], request: Request[T], adminUserId: Option[Id[User]] = None, allowPending: Boolean) = {
    val user = db.readOnly(implicit s => userRepo.get(userId))
    if (experiments.contains(ExperimentTypes.BLOCK) ||
      user.state == UserStates.BLOCKED ||
      user.state == UserStates.INACTIVE ||
      (!allowPending && user.state == UserStates.PENDING)) {
      val message = "user %s access is forbidden".format(userId)
      log.warn(message)
      Forbidden(message)
    } else {
      try {
        action(AuthenticatedRequest[T](identity, userId, user, request, experiments, kifiInstallationId, adminUserId)) match {
          case r: PlainResult => newSession map r.withSession getOrElse r
          case any: Result => any
        }
      } catch {
        case e: Throwable =>
        val globalError = airbrake.notify(AirbrakeError(request, e,
            s"Error executing with userId $userId, experiments [${experiments.mkString(",")}], installation ${kifiInstallationId.getOrElse("NA")}"))
        log.error(s"error reported [${globalError.id}]", e)
          throw ReportedException(globalError.id, e)
      }
    }
  }
}
