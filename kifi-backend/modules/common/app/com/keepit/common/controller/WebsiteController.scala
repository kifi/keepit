package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.http.ContentTypes
import play.api.mvc._
import play.api.libs.json._


class WebsiteController(override val actionAuthenticator: ActionAuthenticator) extends Controller with JsonActions with Logging {

  def AuthenticatedHtmlAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = AuthenticatedHtmlAction(false)(action)

  def AuthenticatedHtmlAction(allowPending: Boolean)(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = Action { request =>
    actionAuthenticator.authenticatedAction(false, allowPending, parse.anyContent, action)(request) match {
      case r: PlainResult => r.as(ContentTypes.HTML)
      case any => any
    }
  }

  def AuthenticatedAction[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = true)(action: AuthenticatedRequest[T] => Result): Action[T] =
    actionAuthenticator.authenticatedAction(apiClient, false, parser, action)

  def HtmlAction(authenticatedAction: AuthenticatedRequest[AnyContent] => Result, unauthenticatedAction: Request[AnyContent] => Result): Action[AnyContent] =
    HtmlAction(false)(authenticatedAction, unauthenticatedAction)

  def HtmlAction(allowPending: Boolean)(authenticatedAction: AuthenticatedRequest[AnyContent] => Result, unauthenticatedAction: Request[AnyContent] => Result): Action[AnyContent] = Action { request =>
    actionAuthenticator.authenticatedAction(false, allowPending, parse.anyContent, authenticatedAction, unauthenticatedAction)(request) match {
      case r: PlainResult => r.as(ContentTypes.HTML)
      case any => any
    }
  }

}
