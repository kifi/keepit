package com.keepit.controllers.admin
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.AdminController
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import views.html

@Singleton
class AdminSearchPerformanceController @Inject() (
    val userActionsHelper: UserActionsHelper) extends AdminUserActions {
  def viewSearchPerformance = AdminUserPage { implicit request =>
    Ok(html.admin.searchPerformance())
  }
}