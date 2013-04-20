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
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social._
import com.keepit.model._

import play.api.i18n.Messages
import play.api.mvc._
import play.api.libs.json.JsNumber
import securesocial.core._

object ActionAuthenticator {
  val FORTYTWO_USER_ID = "fortytwo_user_id"
}

@Singleton
class ActionAuthenticator @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userExperimentRepo: UserExperimentRepo,
  userRepo: UserRepo,
  fortyTwoServices: FortyTwoServices,
  healthcheckPlugin: HealthcheckPlugin)
    extends SecureSocial with Logging {

  private def loadUserId(userIdOpt: Option[Id[User]], socialId: SocialId)(implicit session: RSession) = {
    userIdOpt match {
      case None =>
        val socialUser = socialUserInfoRepo.get(socialId, SocialNetworks.FACEBOOK)
        val userId = socialUser.userId.get
        userId
      case Some(userId) =>
        val socialUser = socialUserInfoRepo.get(socialId, SocialNetworks.FACEBOOK)
        if (socialUser.userId.get != userId) {
          log.error("Social user id %s does not match session user id %s".format(socialUser, userId))
        }
        userId
    }
  }

  private def loadUserContext(userIdOpt: Option[Id[User]], socialId: SocialId) = db.readOnly{ implicit session =>
    val userId = loadUserId(userIdOpt, socialId)
    (userId, getExperiments(userId))
  }

  private def getExperiments(userId: Id[User])(implicit session: RSession): Seq[State[ExperimentType]] = userExperimentRepo.getUserExperiments(userId)

  private def authenticatedHandler[T](apiClient: Boolean, allowPending: Boolean)(authAction: AuthenticatedRequest[T] => Result) = { implicit request: SecuredRequest[T] => /* onAuthenticated */
      val userIdOpt = request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map{id => Id[User](id.toLong)}
      val impersonatedUserIdOpt: Option[ExternalId[User]] = ImpersonateCookie.decodeFromCookie(request.cookies.get(ImpersonateCookie.COOKIE_NAME))
      val kifiInstallationId: Option[ExternalId[KifiInstallation]] = KifiInstallationCookie.decodeFromCookie(request.cookies.get(KifiInstallationCookie.COOKIE_NAME))
      val socialUser = request.user
      val (userId, experiments) = loadUserContext(userIdOpt, SocialId(socialUser.id.id))
      val newSession = session + (ActionAuthenticator.FORTYTWO_USER_ID -> userId.toString)
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
      onUnauthenticated: Request[T] => Result): Action[T] = UserAwareAction(bodyParser) { request =>
    request.user match {
      case Some(user) =>
        authenticatedHandler(apiClient, allowPending)(onAuthenticated)(SecuredRequest(user, request))
      case None =>
        onUnauthenticated(request)
    }
  }

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

  private[controller] def isAdmin(experiments: Seq[State[ExperimentType]]) = experiments.find(e => e == ExperimentTypes.ADMIN).isDefined

  private[controller] def isAdmin(userId: Id[User]) = db.readOnly { implicit session =>
    userExperimentRepo.hasExperiment(userId, ExperimentTypes.ADMIN)
  }

  private def executeAction[T](action: AuthenticatedRequest[T] => Result, userId: Id[User], identity: Identity,
      experiments: Seq[State[ExperimentType]], kifiInstallationId: Option[ExternalId[KifiInstallation]],
      newSession: Session, request: Request[T], adminUserId: Option[Id[User]] = None, allowPending: Boolean) = {
    val user = db.readOnly(implicit s => userRepo.get(userId))
    if (experiments.contains(ExperimentTypes.BLOCK) || user.state == UserStates.BLOCKED || user.state == UserStates.INACTIVE || (!allowPending && user.state == UserStates.PENDING)) {
      val message = "user %s access is forbidden".format(userId)
      log.warn(message)
      Forbidden(message)
    } else {
      val cleanedSesison = newSession - IdentityProvider.SessionId + ("server_version" -> fortyTwoServices.currentVersion.value)
      log.debug("sending response with new session [%s] of user id: %s".format(cleanedSesison, userId))
      try {
        action(AuthenticatedRequest[T](identity, userId, user, request, experiments, kifiInstallationId, adminUserId)) match {
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
}
