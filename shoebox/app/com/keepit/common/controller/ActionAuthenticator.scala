package com.keepit.common.controller

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}

import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.libs.json.JsValue
import play.api.mvc._
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.libs.iteratee.Parsing._
import play.api.libs.json._
import play.api.mvc.Results.InternalServerError
import securesocial.core._

import com.google.inject.{Inject, Singleton}

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

  private[controller] def authenticatedAction[T](bodyParser: BodyParser[T])(isApi: Boolean, action: AuthenticatedRequest[T] => Result): Action[T] = {
    SecuredAction(isApi, bodyParser) { implicit request =>
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
          executeAction(action, impUserId, impSocialUser, impExperiments, kifiInstallationId, newSession, request.request, Some(userId))
        case None =>
          executeAction(action, userId, socialUser, experiments, kifiInstallationId, newSession, request.request)
      }
    }
  }

  private[controller] def isAdmin(experiments: Seq[State[ExperimentType]]) = experiments.find(e => e == ExperimentTypes.ADMIN).isDefined

  private[controller] def isAdmin(userId: Id[User]) = db.readOnly { implicit session =>
    userExperimentRepo.hasExperiment(userId, ExperimentTypes.ADMIN)
  }

  private def executeAction[T](action: AuthenticatedRequest[T] => Result, userId: Id[User], socialUser: SocialUser,
      experiments: Seq[State[ExperimentType]], kifiInstallationId: Option[ExternalId[KifiInstallation]],
      newSession: Session, request: Request[T], adminUserId: Option[Id[User]] = None) = {
    if (experiments.contains(ExperimentTypes.BLOCK)) {
      val message = "user %s access is forbidden".format(userId)
      log.warn(message)
      Forbidden(message)
    } else {
      val cleanedSesison = newSession - IdentityProvider.SessionId + ("server_version" -> fortyTwoServices.currentVersion.value)
      log.debug("sending response with new session [%s] of user id: %s".format(cleanedSesison, userId))
      try {
        action(AuthenticatedRequest[T](socialUser, userId, request, experiments, kifiInstallationId, adminUserId)) match {
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
