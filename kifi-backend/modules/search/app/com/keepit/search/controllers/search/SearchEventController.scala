package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.tracking.SearchEventCommander
import play.api.mvc.Action

class SearchEventController @Inject() (searchEventCommander: SearchEventCommander) extends SearchServiceController {

  def updateBrowsingHistory(userId: Id[User]) = Action(parse.tolerantJson) { request =>
    // decommissioned
    Ok
  }
}
