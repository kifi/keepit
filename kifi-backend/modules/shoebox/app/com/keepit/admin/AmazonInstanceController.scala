package com.keepit.controllers.admin

import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.google.inject.Inject

import views.html

class AmazonInstanceController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    amazonInstanceInfo: AmazonInstanceInfo) extends AdminController(actionAuthenticator) {

  def instanceInfo = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.amazonInstanceInfo(amazonInstanceInfo))
  }

}
