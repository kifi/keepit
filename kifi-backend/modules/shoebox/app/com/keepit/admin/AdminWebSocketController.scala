package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import views.html

@Singleton
class AdminWebSocketController @Inject() (actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator) {
  def serviceView = AdminHtmlAction { implicit request =>
    Ok(html.admin.websocketPerformance())
  }
}

