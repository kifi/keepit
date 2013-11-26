package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.http.ContentTypes
import play.api.mvc._
import play.api.libs.json._

class MobileController(actionAuthenticator: ActionAuthenticator) extends Controller with Logging {
  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedJsonAction(false, parse.anyContent)(action)

  def AuthenticatedJsonAction(allowPending: Boolean)(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
    AuthenticatedJsonAction(allowPending, parse.anyContent)(action)

  def AuthenticatedJsonAction[T](bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] =
    AuthenticatedJsonAction(false, bodyParser)(action)

  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
    AuthenticatedJsonAction(false, parse.tolerantJson)(action)

  def AuthenticatedJsonAction[T](allowPending: Boolean, bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] = Action(bodyParser) { request =>
    actionAuthenticator.authenticatedAction(true, allowPending, bodyParser, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.JSON)
      case any => any
    }
  }
}

