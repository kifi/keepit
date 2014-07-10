package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.search.SearchServiceClient

class AdminUserIndexController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    searchClient: SearchServiceClient) extends AdminController(actionAuthenticator) {
  def reindex() = AdminHtmlAction.authenticated { implicit request =>
    searchClient.reindexUsers()
    Ok("reindexing users")
  }
}
