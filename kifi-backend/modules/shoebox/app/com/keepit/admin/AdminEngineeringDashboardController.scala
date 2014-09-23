package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.AdminController
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import views.html

class AdminEngineeringDashboardController @Inject() (
    val userActionsHelper: UserActionsHelper) extends AdminUserActions {
  def overview = AdminUserPage { implicit request =>
    Ok(html.admin.engineeringDashboard())
  }
  def seyren = AdminUserPage { implicit request =>
    Ok(html.admin.seyren())
  }
}