package com.keepit.common.controller

import com.keepit.common.logging.Logging

import play.api._
import play.api.Play.current
import play.api.http.ContentTypes
import play.api.mvc._

class AdminController(val actionAuthenticator: ActionAuthenticator) extends ServiceController with ActionsBuilder with ShoeboxServiceController {

  object AdminJsonAction extends Actions.AuthenticatedActions {
    override val contentTypeOpt = Some(ContentTypes.JSON)
  }
  object AdminHtmlAction extends Actions.AuthenticatedActions {
    override val contentTypeOpt = Some(ContentTypes.HTML)
    override val apiClient = false
    override def globalAuthFilter[T](request: AuthenticatedRequest[T]) = {
      val userId = request.adminUserId.getOrElse(request.userId)
      val authorizedDevUser = Play.isDev && userId.id == 1L
      authorizedDevUser || actionAuthenticator.isAdmin(userId)
    }
  }
  object AnyAction extends Actions.NonAuthenticatedActions

//
//  def AdminHtmlAction(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] = AdminAction(false, action)
//
//  def AdminJsonAction(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] = Action(parse.anyContent) { request =>
//    AdminAction(false, action)(request) match {
//      case r: SimpleResult => r.as(ContentTypes.JSON)
//    }
//  }
//
//  def AdminCsvAction(filename: String)(action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] =
//      Action(parse.anyContent) { request =>
//    AdminAction(true, action)(request) match {
//      case r: SimpleResult => r.withHeaders(
//        "Content-Type" -> "text/csv",
//        "Content-Disposition" -> s"attachment; filename='$filename'"
//      )
//    }
//  }
//
//  private[controller] def AdminAction(isApi: Boolean, action: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] = {
//    actionAuthenticator.authenticatedAction(isApi, true, parse.anyContent, onAuthenticated = { implicit request =>
//      val userId = request.adminUserId.getOrElse(request.userId)
//      val authorizedDevUser = Play.isDev && userId.id == 1L
//      if (authorizedDevUser || actionAuthenticator.isAdmin(userId)) {
//        action(request)
//      } else {
//        Unauthorized("""User %s does not have admin auth in %s mode, flushing session...
//            If you think you should see this page, please contact FortyTwo Engineering.""".format(userId, current.mode)).withNewSession
//      }
//    })
//  }
}


