package com.keepit.controllers.admin

import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.controller.{ AdminController, UserActionsHelper, AdminUserActions }
import com.google.inject.Inject

import views.html

class AmazonInstanceController @Inject() (
    val userActionsHelper: UserActionsHelper,
    amazonInstanceInfo: AmazonInstanceInfo) extends AdminUserActions {

  def instanceInfo = AdminUserPage { implicit request =>
    Ok(html.admin.amazonInstanceInfo(amazonInstanceInfo))
  }

}
