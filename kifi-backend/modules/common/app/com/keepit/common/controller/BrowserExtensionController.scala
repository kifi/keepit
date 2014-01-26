package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.http.ContentTypes
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.Future
import securesocial.core.{Authorization, UserService, SecureSocial, RequestWithUser}
import play.api.libs.iteratee.{Input, Done}

abstract class BrowserExtensionController(actionAuthenticator: ActionAuthenticator) extends ServiceController with Logging {
  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] =
    AuthenticatedJsonAction(parse.anyContent)(action)

  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => SimpleResult): Action[JsValue] =
    AuthenticatedJsonAction(parse.tolerantJson)(action)

  def AuthenticatedJsonAction[T](bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => SimpleResult): Action[T] = Action(bodyParser) { request =>
    action.andThen(Future.successful)
    actionAuthenticator.authenticatedAction(true, false, bodyParser, action)(request) match {
      case r: SimpleResult => r.as(ContentTypes.JSON)
    }
  }

  def JsonAction[T](allowPending: Boolean, parser: BodyParser[T] = parse.anyContent)(authenticatedAction: AuthenticatedRequest[T] => SimpleResult, unauthenticatedAction: Request[T] => SimpleResult): Action[T] =
    actionAuthenticator.authenticatedAction(true, allowPending, parser, authenticatedAction, unauthenticatedAction)

  def JsonToJsonAction(authenticatedAction: AuthenticatedRequest[JsValue] => SimpleResult, unauthenticatedAction: Request[JsValue] => SimpleResult): Action[JsValue] =
    JsonToJsonAction(false)(authenticatedAction, unauthenticatedAction)

  def JsonToJsonAction(allowPending: Boolean)(authenticatedAction: AuthenticatedRequest[JsValue] => SimpleResult, unauthenticatedAction: Request[JsValue] => SimpleResult): Action[JsValue] =
    actionAuthenticator.authenticatedAction(false, allowPending, parse.tolerantJson, authenticatedAction, unauthenticatedAction)

  def AuthenticatedHtmlAction(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] =
    actionAuthenticator.authenticatedAction(false, false, parse.anyContent, action)

}

