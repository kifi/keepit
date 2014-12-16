package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }

class SearchAdminController @Inject() (
  val userActionsHelper: UserActionsHelper,
  amazonInstanceInfo: AmazonInstanceInfo)
    extends AdminUserActions {

  def instance() = AdminUserPage { request =>
    Ok(amazonInstanceInfo.name.getOrElse(""))
  }

}
