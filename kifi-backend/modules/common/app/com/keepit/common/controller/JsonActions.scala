package com.keepit.common.controller

import play.api.libs.json.JsValue
import play.api.mvc._
import play.api.mvc.BodyParsers.parse
import play.api.http.ContentTypes

trait JsonActions {

  //  def actionAuthenticator:ActionAuthenticator
  //
  //  def AuthenticatedJsonAction(allowPending: Boolean)(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
  //    AuthenticatedJsonAction(allowPending, parse.anyContent)(action)
  //
  //  def AuthenticatedJsonToJsonAction(allowPending: Boolean)(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
  //    AuthenticatedJsonAction(allowPending, parse.tolerantJson)(action)
  //
  //  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] =
  //    AuthenticatedJsonAction(false, parse.anyContent)(action)
  //
  //  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => Result): Action[JsValue] =
  //    AuthenticatedJsonAction(false, parse.tolerantJson)(action)
  //
  //  def AuthenticatedJsonAction[T](bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] =
  //    AuthenticatedJsonAction(false, bodyParser)(action)
  //
  //  def AuthenticatedJsonAction[T](allowPending: Boolean, bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => Result): Action[T] = Action(bodyParser) { request =>
  //    actionAuthenticator.authenticatedAction(true, allowPending, bodyParser, action)(request) match {
  //      case r: Result => r.as(ContentTypes.JSON)
  //    }
  //  }
  //
  //  def JsonToJsonAction(allowPending: Boolean)
  //                      (authenticatedAction: AuthenticatedRequest[JsValue] => Result,
  //                       unauthenticatedAction: Request[JsValue] => Result): Action[JsValue] =
  //    actionAuthenticator.authenticatedAction(true, allowPending, parse.tolerantJson, authenticatedAction, unauthenticatedAction)
  //
  //  def JsonAction[T](allowPending: Boolean, parser: BodyParser[T] = parse.anyContent)
  //                   (authenticatedAction: AuthenticatedRequest[T] => Result,
  //                    unauthenticatedAction: Request[T] => Result): Action[T] =
  //    actionAuthenticator.authenticatedAction(true, allowPending, parser, authenticatedAction, unauthenticatedAction)

}
