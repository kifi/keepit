package com.keepit.controllers.admin
import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.AdminController
import com.keepit.common.controller.ActionAuthenticator
import views.html


@Singleton
class AdminEngineeringDashboardController @Inject() (
  actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator) {
  def overview = AdminHtmlAction { implicit request =>
      Ok(html.admin.engineeringDashboard())
  }
}