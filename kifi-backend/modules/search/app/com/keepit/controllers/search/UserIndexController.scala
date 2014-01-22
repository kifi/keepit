package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.search.user.UserIndexerPlugin
import play.api.mvc.Action


class UserIndexController @Inject()(
  indexer: UserIndexerPlugin
) extends SearchServiceController {
  def reindex() = Action { implicit request =>
    indexer.reindex()
    Ok
  }
}
