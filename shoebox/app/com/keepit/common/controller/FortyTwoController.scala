package com.keepit.common.controller

import play.api.data._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.mvc.Results.InternalServerError
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.inject._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.net._
import com.keepit.common.db.{Id, CX, ExternalId, State}
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.controllers.CommonActions._
import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.common.social._
import views.html.defaultpages.unauthorized

case class ReportedException(val id: ExternalId[HealthcheckError], val cause: Throwable) extends Exception(id.toString, cause)

object FortyTwoController {

  val FORTYTWO_USER_ID = "fortytwo_user_id"

  object ImpersonateCookie extends CookieBaker[Option[ExternalId[User]]] {
    val COOKIE_NAME = "impersonating"
    val emptyCookie = None
    override val isSigned = true
    override val secure = false
    override val maxAge = -1
    override val httpOnly = true
    def deserialize(data: Map[String, String]) = data.get(COOKIE_NAME).map(ExternalId[User](_))
    def serialize(data: Option[ExternalId[User]]) = data.map(id => Map(COOKIE_NAME -> id.id.toString())).getOrElse(Map.empty)
  }

  object KifiInstallationCookie extends CookieBaker[Option[ExternalId[KifiInstallation]]] {
    val COOKIE_NAME = "installation"
    val emptyCookie = None
    override val isSigned = true
    override val secure = false
    def deserialize(data: Map[String, String]) = data.get(COOKIE_NAME).map(ExternalId[KifiInstallation](_))
    def serialize(data: Option[ExternalId[KifiInstallation]]) = data.map(id => Map(COOKIE_NAME -> id.id.toString())).getOrElse(Map.empty)
  }
}

trait FortyTwoController extends Controller with Logging with SecureSocial {
  import FortyTwoController._

  case class AuthenticatedRequest(
      socialUser: SocialUser,
      userId: Id[User],
      request: Request[AnyContent],
      experimants: Seq[State[UserExperiment.ExperimentType]] = Nil,
      kifiInstallationId: Option[ExternalId[KifiInstallation]] = None,
      adminUserId: Option[Id[User]] = None)
    extends WrappedRequest(request)

  def AuthenticatedJsonAction(action: AuthenticatedRequest => Result): Action[AnyContent] = Action(parse.anyContent) { request =>
    AuthenticatedAction(true, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }

  def AuthenticatedHtmlAction(action: AuthenticatedRequest => Result): Action[AnyContent] =
    AuthenticatedAction(false, action)

  private def loadUserId(userIdOpt: Option[Id[User]], socialId: SocialId)(implicit conn: Connection) = {
    userIdOpt match {
      case None =>
        val socialUser = SocialUserInfo.get(socialId, SocialNetworks.FACEBOOK)
        val userId = socialUser.userId.get
        userId
      case Some(userId) =>
        val socialUser = SocialUserInfo.get(socialId, SocialNetworks.FACEBOOK)
        if (socialUser.userId.get != userId) log.error("Social user id %s does not match session user id %s".format(socialUser, userId))
        userId
    }
  }

  private def loadUserContext(userIdOpt: Option[Id[User]], socialId: SocialId) = CX.withConnection { implicit conn =>
    val userId = loadUserId(userIdOpt, socialId)
    (userId, getExperiments(userId))
  }

  private def getExperiments(userId: Id[User])(implicit conn: Connection): Seq[State[UserExperiment.ExperimentType]] =
    UserExperiment.getByUser(userId).map(_.experimentType)

  private[controller] def AuthenticatedAction[A](isApi: Boolean, action: AuthenticatedRequest => Result) = {
    SecuredAction(isApi, parse.anyContent) { implicit request =>
      val userIdOpt = request.session.get(FORTYTWO_USER_ID).map{id => Id[User](id.toLong)}
      val impersonatedUserIdOpt: Option[ExternalId[User]] = ImpersonateCookie.decodeFromCookie(request.cookies.get(ImpersonateCookie.COOKIE_NAME))
      val kifiInstallationId: Option[ExternalId[KifiInstallation]] = KifiInstallationCookie.decodeFromCookie(request.cookies.get(KifiInstallationCookie.COOKIE_NAME))
      val socialUser = request.user
      val (userId, experiments) = loadUserContext(userIdOpt, SocialId(socialUser.id.id))
      val newSession = session + (FORTYTWO_USER_ID -> userId.toString)
      impersonatedUserIdOpt match {
        case Some(impExternalUserId) =>
          val (impExperiments, impSocialUser, impUserId) = CX.withConnection { implicit conn =>
            val impUserId = User.get(impExternalUserId).id.get

            if (!isAdmin(experiments)) throw new IllegalStateException("non admin user %s tries to impersonate to %s".format(userId, impUserId))
            val impSocialUserInfo = SocialUserInfo.getByUser(impUserId).head
            (getExperiments(impUserId), impSocialUserInfo.credentials.get, impUserId)
          }
          log.info("[IMPERSONATOR] admin user %s is impersonating user %s with request %s".format(userId, impSocialUser, request.request.path))
          executeAction(action, impUserId, impSocialUser, impExperiments, kifiInstallationId, newSession, request.request, Some(userId))
        case None =>
          executeAction(action, userId, socialUser, experiments, kifiInstallationId, newSession, request.request)
      }
    }
  }

  private def isAdmin(experiments: Seq[State[UserExperiment.ExperimentType]]) =
    experiments.find(e => e == UserExperiment.ExperimentTypes.ADMIN).isDefined

  private def executeAction(action: AuthenticatedRequest => Result, userId: Id[User], socialUser: SocialUser,
      experiments: Seq[State[UserExperiment.ExperimentType]], kifiInstallationId: Option[ExternalId[KifiInstallation]],
      newSession: Session, request: Request[AnyContent], adminUserId: Option[Id[User]] = None) = {
    if (experiments.contains(UserExperiment.ExperimentTypes.BLOCK)) {
      val message = "user %s access is forbidden".format(userId)
      log.warn(message)
      Forbidden(message)
    } else {
      val cleanedSesison = newSession - IdentityProvider.SessionId + ("server_version" -> inject[FortyTwoServices].currentVersion.value)
      log.debug("sending response with new session [%s] of user id: %s".format(cleanedSesison, userId))
      try {
        action(AuthenticatedRequest(socialUser, userId, request, experiments, kifiInstallationId, adminUserId)) match {
          case r: PlainResult => r.withSession(newSession)
          case any => any
        }
      } catch {
        case e: Throwable =>
          val globalError = inject[HealthcheckPlugin].addError(HealthcheckError(
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

  def AdminJsonAction(action: AuthenticatedRequest => Result): Action[AnyContent] = Action(parse.anyContent) { request =>
    AdminAction(true, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }

  def AdminHtmlAction(action: AuthenticatedRequest => Result): Action[AnyContent] = AdminAction(false, action)

  private[controller] def AdminAction(isApi: Boolean, action: AuthenticatedRequest => Result): Action[AnyContent] = {
    AuthenticatedAction(isApi, { implicit request =>
      val userId = request.adminUserId.getOrElse(request.userId)
      val isAdmin = CX.withConnection { implicit conn =>
        UserExperiment.getExperiment(userId, UserExperiment.ExperimentTypes.ADMIN).isDefined
      }
      val authorizedDevUser = Play.isDev && userId.id == 1L
      if (authorizedDevUser || isAdmin) {
        action(request)
      } else {
        Unauthorized("""User %s does not have admin auth in %s mode, flushing session...
            If you think you should see this page, please contact FortyTwo Engineering.""".format(userId, current.mode)).withNewSession
      }
    })
  }

}

