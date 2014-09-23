package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.search.SearchServiceClient

class AdminUserIndexController @Inject() (
    val userActionsHelper: UserActionsHelper,
    searchClient: SearchServiceClient) extends AdminUserActions {
  def reindex() = AdminUserPage { implicit request =>
    searchClient.reindexUsers()
    Ok("reindexing users")
  }
}
