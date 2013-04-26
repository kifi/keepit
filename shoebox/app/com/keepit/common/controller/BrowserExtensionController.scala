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

  def JsonToJsonAction(authenticatedAction: AuthenticatedRequest[JsValue] => Result, unauthenticatedAction: Request[JsValue] => Result): Action[JsValue] =
    JsonToJsonAction(false)(authenticatedAction, unauthenticatedAction)

  def JsonToJsonAction(allowPending: Boolean)(authenticatedAction: AuthenticatedRequest[JsValue] => Result, unauthenticatedAction: Request[JsValue] => Result): Action[JsValue] =
    actionAuthenticator.authenticatedAction(false, allowPending, parse.tolerantJson, authenticatedAction, unauthenticatedAction)

  def AuthenticatedHtmlAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    actionAuthenticator.authenticatedAction(false, false, parse.anyContent, action)
}

