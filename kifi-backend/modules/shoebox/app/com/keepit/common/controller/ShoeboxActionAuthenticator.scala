package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.FortyTwoCookies.{ KifiInstallationCookie, ImpersonateCookie }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, State, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ UserAgent, URI }
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.social.{ SocialNetworkType, SocialId }
import com.keepit.commanders.LocalUserExperimentCommander
import play.api.mvc._
import securesocial.core._
import scala.concurrent.Future

@Singleton
class ShoeboxActionAuthenticator @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userExperimentCommander: LocalUserExperimentCommander,
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
        if (socialUser.userId.isDefined && socialUser.userId.get != userId) {
          log.error(s"Social user id $socialUser does not match session user id $userId")
        }
        Some(userId)
    }
  }

  private def getExperiments(userId: Id[User])(implicit session: RSession): Set[ExperimentType] = userExperimentCommander.getExperimentsByUser(userId)

  private def authenticatedHandler[T](userId: Id[User], apiClient: Boolean, allowPending: Boolean)(authAction: AuthenticatedRequest[T] => Future[Result]) = { implicit request: SecuredRequest[T] => /* onAuthenticated */
    val socialUser = request.user
    val impersonatedUserIdOpt: Option[ExternalId[User]] = impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
    val kifiInstallationId: Option[ExternalId[KifiInstallation]] = kifiInstallationCookie.decodeFromCookie(request.cookies.get(kifiInstallationCookie.COOKIE_NAME))
    val experiments = db.readOnlyMaster { implicit s => getExperiments(userId) }
    val newSession =
      if (session.get(ActionAuthenticator.FORTYTWO_USER_ID) == Some(userId.toString)) None
      else Some(session + (ActionAuthenticator.FORTYTWO_USER_ID -> userId.toString))
    impersonatedUserIdOpt match {
      case Some(impExternalUserId) =>
        val (impExperiments, impSocialUser, impUserId) = db.readOnlyReplica { implicit session =>
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
    onAuthenticated: AuthenticatedRequest[T] => Future[Result],
    onSocialAuthenticated: SecuredRequest[T] => Future[Result],
    onUnauthenticated: Request[T] => Future[Result]): Action[T] = SecureSocialUserAwareAction.async(bodyParser) { request =>
    val result = request.user match {
      case Some(identity) =>
        val userIdOpt = request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map { id => Id[User](id.toLong) }
        val uidOpt = db.readOnlyMaster { implicit s =>
          loadUserId(userIdOpt, SocialId(identity.identityId.userId), SocialNetworkType(identity.identityId.providerId))
        }
        uidOpt match {
          case Some(userId) =>
            authenticatedHandler(userId, apiClient, allowPending)(onAuthenticated)(SecuredRequest(identity, request))
          case None =>
            onSocialAuthenticated(SecuredRequest(identity, request))
        }
      case None =>
        log.warn(s"unknown user access is unauthenticated while access ${request.path}")
        onUnauthenticated(request)
    }
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com")
    }.map { h =>
      import com.keepit.common.concurrent.ExecutionContext.immediate
      result.map(_.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true"
      ))(immediate)
    }.getOrElse(result)
  }

  private[controller] def isAdmin(experiments: Set[ExperimentType]) = experiments.contains(ExperimentType.ADMIN)

  private[controller] def isAdmin(userId: Id[User]) = db.readOnlyReplica { implicit session =>
    userExperimentCommander.userHasExperiment(userId, ExperimentType.ADMIN)
  }

  private def executeAction[T](action: AuthenticatedRequest[T] => Future[Result], userId: Id[User], identity: Identity,
    experiments: Set[ExperimentType], kifiInstallationId: Option[ExternalId[KifiInstallation]],
    newSession: Option[Session], request: Request[T], adminUserId: Option[Id[User]] = None, allowPending: Boolean) = {
    val user = db.readOnlyMaster(implicit s => userRepo.get(userId))
    if (user.state == UserStates.BLOCKED ||
      user.state == UserStates.INACTIVE ||
      (!allowPending && (user.state == UserStates.PENDING || user.state == UserStates.INCOMPLETE_SIGNUP))) {
      log.warn(s"user $userId access is forbidden/blocked while access ${request.path}")
      Future.successful(Redirect("/logout"))
    } else {
      try {
        import com.keepit.common.concurrent.ExecutionContext.immediate
        val result = action(AuthenticatedRequest[T](identity, userId, user, request, experiments, kifiInstallationId, adminUserId))
        result.map { res =>
          newSession match {
            case Some(sess) => res.withSession(sess)
            case None => res
          }
        }(immediate)
      } catch {
        case e: Throwable =>
          val globalError = airbrake.notify(AirbrakeError.incoming(request, e,
            s"Error executing with user https://admin.kifi.com/admin/user/$userId ${identity.fullName}, ${request.headers.get(USER_AGENT).getOrElse("NO USER AGENT")}"))
          log.error(s"error reported [${globalError.id}]", e)
          throw ReportedException(globalError.id, e)
      }
    }
  }
}
