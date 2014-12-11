package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.search._
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits._

class SearchEventController @Inject() (searchEventCommander: SearchEventCommander) extends SearchServiceController {

  def updateBrowsingHistory(userId: Id[User]) = Action(parse.tolerantJson) { request =>
    // decommissioned
    Ok
  }
}
