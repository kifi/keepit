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

case class ReportedException(val id: ExternalId[HealthcheckError], val cause: Throwable) extends Exception(id.toString, cause)

case class AuthenticatedRequest[T](
    socialUser: SocialUser,
    userId: Id[User],
    user: User,
    request: Request[T],
    experimants: Seq[State[ExperimentType]] = Nil,
    kifiInstallationId: Option[ExternalId[KifiInstallation]] = None,
    adminUserId: Option[Id[User]] = None)
  extends WrappedRequest(request)

class AdminController(actionAuthenticator: ActionAuthenticator) extends Controller with Logging with ShoeboxServiceController {

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
    actionAuthenticator.authenticatedAction(isApi, true, parse.anyContent, onAuthenticated = { implicit request =>
      val userId = request.adminUserId.getOrElse(request.userId)
      val authorizedDevUser = Play.isDev && userId.id == 1L
      if (authorizedDevUser || actionAuthenticator.isAdmin(userId)) {
        action(request)
      } else {
        Unauthorized("""User %s does not have admin auth in %s mode, flushing session...
            If you think you should see this page, please contact FortyTwo Engineering.""".format(userId, current.mode)).withNewSession
      }
    })
  }
}

class BrowserExtensionController(actionAuthenticator: ActionAuthenticator) extends Controller with Logging {
  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedJsonAction(parse.anyContent)(action)

  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
    AuthenticatedJsonAction(parse.tolerantJson)(action)

  def AuthenticatedJsonAction[T](bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] = Action(bodyParser) { request =>
    actionAuthenticator.authenticatedAction(true, false, bodyParser, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }

  def AuthenticatedHtmlAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    actionAuthenticator.authenticatedAction(false, false, parse.anyContent, action)
}

class WebsiteController(actionAuthenticator: ActionAuthenticator) extends Controller with Logging {
  
  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedJsonAction(parse.anyContent)(action)

  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
    AuthenticatedJsonAction(parse.tolerantJson)(action)

  def AuthenticatedJsonAction[T](bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] = Action(bodyParser) { request =>
    actionAuthenticator.authenticatedAction(true, false, bodyParser, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }
  
  def AuthenticatedHtmlAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    actionAuthenticator.authenticatedAction(false, false, parse.anyContent, action)

  def HtmlAction(authenticatedAction: AuthenticatedRequest[AnyContent] => Result, unauthenticatedAction: Request[AnyContent] => Result): Action[AnyContent] =
    HtmlAction(false)(authenticatedAction, unauthenticatedAction)

  def HtmlAction(allowPending: Boolean)(authenticatedAction: AuthenticatedRequest[AnyContent] => Result, unauthenticatedAction: Request[AnyContent] => Result): Action[AnyContent] =
    actionAuthenticator.authenticatedAction(false, allowPending, parse.anyContent, authenticatedAction, unauthenticatedAction)
}

trait SearchServiceController extends Controller with Logging
trait ShoeboxServiceController extends Controller with Logging
