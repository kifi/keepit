package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ HttpProxyStates, HttpProxy, URLRepo, NormalizedURIRepo }
import com.keepit.rover.RoverServiceClient

import scala.concurrent.ExecutionContext

class AdminRoverController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val uriRepo: NormalizedURIRepo,
    val urlRepo: URLRepo,
    val roverServiceClient: RoverServiceClient,
    implicit val executionContext: ExecutionContext) extends AdminUserActions {

  def findUrl = AdminUserPage { implicit request =>
    ???
  }

  def getAllProxies = AdminUserPage.async { implicit request =>
    roverServiceClient.getAllProxies().map { proxies =>
      Ok(views.html.admin.roverProxies(proxies))
    }
  }

}
