package com.keepit.common.controller

import com.keepit.common.logging.Logging

import play.api._
import play.api.Play.current
import play.api.http.ContentTypes
import play.api.mvc._

class AdminController(actionAuthenticator: ActionAuthenticator) extends Controller with Logging with ShoeboxServiceController {

  def AdminHtmlAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = AdminAction(false, action)

  def AdminJsonAction(action: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = Action(parse.anyContent) { request =>
    AdminAction(false, action)(request) match {
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


