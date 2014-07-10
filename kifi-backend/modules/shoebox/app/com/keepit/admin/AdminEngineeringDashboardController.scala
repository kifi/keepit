package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.AdminController
import com.keepit.common.controller.ActionAuthenticator
import views.html

class AdminEngineeringDashboardController @Inject() (
    actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator) {
  def overview = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.engineeringDashboard())
  }
  def seyren = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.seyren())
  }
}