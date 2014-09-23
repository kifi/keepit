package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.controller.AdminController
import views.html

class AdminWebSocketController @Inject() (val userActionsHelper: UserActionsHelper) extends AdminUserActions {
  def serviceView = AdminUserPage { implicit request =>
    Ok(html.admin.websocketPerformance())
  }
}

