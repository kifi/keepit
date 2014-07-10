package com.keepit.common.controller

import play.api.libs.json.JsValue
import play.api.mvc._
import play.api.mvc.BodyParsers.parse
import play.api.http.ContentTypes

trait JsonActions {

  //  def actionAuthenticator:ActionAuthenticator
  //
  //  def AuthenticatedJsonAction(allowPending: Boolean)(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] =
  //    AuthenticatedJsonAction(allowPending, parse.anyContent)(action)
  //
  //  def AuthenticatedJsonToJsonAction(allowPending: Boolean)(action: AuthenticatedRequest[JsValue] => SimpleResult): Action[JsValue] =
  //    AuthenticatedJsonAction(allowPending, parse.tolerantJson)(action)
  //
  //  def AuthenticatedJsonAction(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] =
  //    AuthenticatedJsonAction(false, parse.anyContent)(action)
  //
  //  def AuthenticatedJsonToJsonAction(action: AuthenticatedRequest[JsValue] => SimpleResult): Action[JsValue] =
  //    AuthenticatedJsonAction(false, parse.tolerantJson)(action)
  //
  //  def AuthenticatedJsonAction[T](bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => SimpleResult): Action[T] =
  //    AuthenticatedJsonAction(false, bodyParser)(action)
  //
  //  def AuthenticatedJsonAction[T](allowPending: Boolean, bodyParser: BodyParser[T])(action: AuthenticatedRequest[T] => SimpleResult): Action[T] = Action(bodyParser) { request =>
  //    actionAuthenticator.authenticatedAction(true, allowPending, bodyParser, action)(request) match {
  //      case r: SimpleResult => r.as(ContentTypes.JSON)
  //    }
  //  }
  //
  //  def JsonToJsonAction(allowPending: Boolean)
  //                      (authenticatedAction: AuthenticatedRequest[JsValue] => SimpleResult,
  //                       unauthenticatedAction: Request[JsValue] => SimpleResult): Action[JsValue] =
  //    actionAuthenticator.authenticatedAction(true, allowPending, parse.tolerantJson, authenticatedAction, unauthenticatedAction)
  //
  //  def JsonAction[T](allowPending: Boolean, parser: BodyParser[T] = parse.anyContent)
  //                   (authenticatedAction: AuthenticatedRequest[T] => SimpleResult,
  //                    unauthenticatedAction: Request[T] => SimpleResult): Action[T] =
  //    actionAuthenticator.authenticatedAction(true, allowPending, parser, authenticatedAction, unauthenticatedAction)

}
