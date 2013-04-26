package com.keepit.common.controller

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


class WebsiteController(actionAuthenticator: ActionAuthenticator) extends Controller with Logging {

  def AuthenticatedJsonAction(allowPending: Boolean)(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedJsonAction(allowPending, parse.anyContent)(action)

  def AuthenticatedJsonToJsonAction(allowPending: Boolean)(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
    AuthenticatedJsonAction(allowPending, parse.tolerantJson)(action)

  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedJsonAction(false, parse.anyContent)(action)

  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
    AuthenticatedJsonAction(false, parse.tolerantJson)(action)

  def AuthenticatedJsonAction[T](allowPending: Boolean, bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] = Action(bodyParser) { request =>
    actionAuthenticator.authenticatedAction(false, allowPending, bodyParser, action)(request) match {
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
