package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.search.index.user.UserIndexerPlugin
import play.api.mvc.Action

class UserIndexController @Inject() (
    indexer: UserIndexerPlugin) extends SearchServiceController {
  def reindex() = Action { implicit request =>
    indexer.reindex()
    Ok
  }

  def update() = Action { implicit request =>
    indexer.update()
    Ok("update user index")
  }
}
