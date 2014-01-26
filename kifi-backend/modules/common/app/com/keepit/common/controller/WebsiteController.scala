package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.http.ContentTypes
import play.api.mvc._
import play.api.libs.json._


abstract class WebsiteController(override val actionAuthenticator: ActionAuthenticator) extends ServiceController with JsonActions with Logging {

  def AuthenticatedHtmlAction(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] = AuthenticatedHtmlAction(false)(action)

  def AuthenticatedHtmlAction(allowPending: Boolean)(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] = Action { request =>
    actionAuthenticator.authenticatedAction(false, allowPending, parse.anyContent, action)(request) match {
      case r: SimpleResult => r.as(ContentTypes.HTML)
    }
  }

  def AuthenticatedAction[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = true)(action: AuthenticatedRequest[T] => SimpleResult): Action[T] =
    actionAuthenticator.authenticatedAction(apiClient, false, parser, action)

  def HtmlAction(authenticatedAction: AuthenticatedRequest[AnyContent] => SimpleResult, unauthenticatedAction: Request[AnyContent] => SimpleResult): Action[AnyContent] =
    HtmlAction(false)(authenticatedAction, unauthenticatedAction)

  def HtmlAction[T](allowPending: Boolean)(authenticatedAction: AuthenticatedRequest[AnyContent] => SimpleResult, unauthenticatedAction: Request[AnyContent] => SimpleResult): Action[AnyContent] = Action { request =>
    actionAuthenticator.authenticatedAction(false, allowPending, parse.anyContent, authenticatedAction, unauthenticatedAction)(request) match {
      case r: SimpleResult => r.as(ContentTypes.HTML)
    }
  }

}
