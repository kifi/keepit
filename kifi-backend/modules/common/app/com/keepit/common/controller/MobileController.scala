package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.http.ContentTypes
import play.api.mvc._
import play.api.libs.json._

class MobileController(actionAuthenticator: ActionAuthenticator) extends Controller with Logging {
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
}

