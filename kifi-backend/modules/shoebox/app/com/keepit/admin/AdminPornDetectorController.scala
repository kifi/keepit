package com.keepit.controllers.admin

import com.keepit.common.controller.AdminController
import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import views.html

class AdminPornDetectorController @Inject()(
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {
  def index() = AdminHtmlAction.authenticated{ implicit request =>
    Ok
  }

  def detect() = AdminHtmlAction.authenticated{ implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("query").get
    val msg = s"fake result for query ${text}: Not Porn"
    Ok(msg)
  }
}
