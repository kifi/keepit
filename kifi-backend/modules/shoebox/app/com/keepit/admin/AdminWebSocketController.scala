package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import views.html

class AdminWebSocketController @Inject() (actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator) {
  def serviceView = AdminHtmlAction { implicit request =>
    Ok(html.admin.websocketPerformance())
  }
}

