package com.keepit.common.controller

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.social._
import com.keepit.inject._
import com.keepit.model._

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

case class ReportedException(val id: ExternalId[HealthcheckError], val cause: Throwable) extends Exception(id.toString, cause)

object FortyTwoController {

  val FORTYTWO_USER_ID = "fortytwo_user_id"

  object ImpersonateCookie extends CookieBaker[Option[ExternalId[User]]] {
    val COOKIE_NAME = "impersonating"
    val emptyCookie = None
    override val isSigned = true
    override val secure = false
    override val maxAge = None
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

case class AuthenticatedRequest[T](
    socialUser: SocialUser,
    userId: Id[User],
    request: Request[T],
    experimants: Seq[State[ExperimentType]] = Nil,
    kifiInstallationId: Option[ExternalId[KifiInstallation]] = None,
    adminUserId: Option[Id[User]] = None)
  extends WrappedRequest(request)

trait AuthenticatedController extends Controller with Logging with SecureSocial {
  import FortyTwoController._

  private def loadUserId(userIdOpt: Option[Id[User]], socialId: SocialId)(implicit session: RSession) = {
    val repo = inject[SocialUserInfoRepo]
    userIdOpt match {
      case None =>
        val socialUser = repo.get(socialId, SocialNetworks.FACEBOOK)
        val userId = socialUser.userId.get
        userId
      case Some(userId) =>
        val socialUser = repo.get(socialId, SocialNetworks.FACEBOOK)
        if (socialUser.userId.get != userId) log.error("Social user id %s does not match session user id %s".format(socialUser, userId))
        userId
    }
  }

  private def loadUserContext(userIdOpt: Option[Id[User]], socialId: SocialId) = inject[Database].readOnly{ implicit session =>
    val userId = loadUserId(userIdOpt, socialId)
    (userId, getExperiments(userId))
  }

  private def getExperiments(userId: Id[User])(implicit session: RSession): Seq[State[ExperimentType]] =
    inject[UserExperimentRepo].getUserExperiments(userId)

  private[controller] def AuthenticatedAction(isApi: Boolean, action: AuthenticatedRequest[AnyContent] => Result) =
    AuthenticatedAction[AnyContent](parse.anyContent)(isApi, action)

  private[controller] def AuthenticatedAction[T](bodyParser: BodyParser[T])(isApi: Boolean, action: AuthenticatedRequest[T] => Result): Action[T] = {
    SecuredAction(isApi, bodyParser) { implicit request =>
      val userIdOpt = request.session.get(FORTYTWO_USER_ID).map{id => Id[User](id.toLong)}
      val impersonatedUserIdOpt: Option[ExternalId[User]] = ImpersonateCookie.decodeFromCookie(request.cookies.get(ImpersonateCookie.COOKIE_NAME))
      val kifiInstallationId: Option[ExternalId[KifiInstallation]] = KifiInstallationCookie.decodeFromCookie(request.cookies.get(KifiInstallationCookie.COOKIE_NAME))
      val socialUser = request.user
      val (userId, experiments) = loadUserContext(userIdOpt, SocialId(socialUser.id.id))
      val newSession = session + (FORTYTWO_USER_ID -> userId.toString)
      impersonatedUserIdOpt match {
        case Some(impExternalUserId) =>
          val (impExperiments, impSocialUser, impUserId) = inject[Database].readOnly { implicit session =>
            val impUserId = inject[UserRepo].get(impExternalUserId).id.get
            if (!isAdmin(experiments)) throw new IllegalStateException("non admin user %s tries to impersonate to %s".format(userId, impUserId))
            val impSocialUserInfo = inject[SocialUserInfoRepo].getByUser(impUserId).head
            (getExperiments(impUserId), impSocialUserInfo.credentials.get, impUserId)
          }
          log.info("[IMPERSONATOR] admin user %s is impersonating user %s with request %s".format(userId, impSocialUser, request.request.path))
          executeAction(action, impUserId, impSocialUser, impExperiments, kifiInstallationId, newSession, request.request, Some(userId))
        case None =>
          executeAction(action, userId, socialUser, experiments, kifiInstallationId, newSession, request.request)
      }
    }
  }

  private def isAdmin(experiments: Seq[State[ExperimentType]]) = experiments.find(e => e == ExperimentTypes.ADMIN).isDefined

  private def executeAction[T](action: AuthenticatedRequest[T] => Result, userId: Id[User], socialUser: SocialUser,
      experiments: Seq[State[ExperimentType]], kifiInstallationId: Option[ExternalId[KifiInstallation]],
      newSession: Session, request: Request[T], adminUserId: Option[Id[User]] = None) = {
    if (experiments.contains(ExperimentTypes.BLOCK)) {
      val message = "user %s access is forbidden".format(userId)
      log.warn(message)
      Forbidden(message)
    } else {
      val cleanedSesison = newSession - IdentityProvider.SessionId + ("server_version" -> inject[FortyTwoServices].currentVersion.value)
      log.debug("sending response with new session [%s] of user id: %s".format(cleanedSesison, userId))
      try {
        action(AuthenticatedRequest[T](socialUser, userId, request, experiments, kifiInstallationId, adminUserId)) match {
          case r: PlainResult => r.withSession(newSession)
          case any: Result => any
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

}

trait AdminController extends AuthenticatedController {

  def AdminHtmlAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = AdminAction(false, action)

  def AdminJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = Action(parse.anyContent) { request =>
    AdminAction(true, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any: Result => any
    }
  }

  def AdminCsvAction(filename: String)(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
      Action(parse.anyContent) { request =>
    AdminAction(true, action)(request) match {
      case r: PlainResult => r.withHeaders(
        "Content-Type" -> "text/csv",
        "Content-Disposition" -> s"attachment; filename='$filename'"
      )
      case any: Result => any
    }
  }

  private[controller] def AdminAction(isApi: Boolean, action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = {
    AuthenticatedAction(isApi, { implicit request =>
      val userId = request.adminUserId.getOrElse(request.userId)
      val isAdmin = inject[Database].readOnly{ implicit session =>
        inject[UserExperimentRepo].hasExperiment(userId, ExperimentTypes.ADMIN)
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

trait BrowserExtensionController extends AuthenticatedController {

  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedJsonAction(parse.anyContent)(action)

  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
    AuthenticatedJsonAction(parse.tolerantJson)(action)

  def AuthenticatedJsonAction[T](bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] = Action(bodyParser) { request =>
    AuthenticatedAction(bodyParser)(true, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }
}

trait WebsiteController extends AuthenticatedController {
  def AuthenticatedHtmlAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedAction(false, action)
}

trait FortyTwoController extends BrowserExtensionController with AdminController with WebsiteController

