package com.keepit.common.controller

import com.keepit.common.logging.Logging

import play.api._
import play.api.Play.current
import play.api.http.ContentTypes
import play.api.mvc._

class AdminController(val actionAuthenticator: ActionAuthenticator) extends ServiceController with ActionsBuilder with ShoeboxServiceController {

  object AdminJsonAction extends Actions.AuthenticatedActions {
    override val contentTypeOpt = Some(ContentTypes.JSON)
    override def globalAuthFilter[T](request: AuthenticatedRequest[T]) = {
      val userId = request.adminUserId.getOrElse(request.userId)
      val authorizedDevUser = Play.isDev && userId.id == 1L
      authorizedDevUser || actionAuthenticator.isAdmin(userId)
    }
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
}

