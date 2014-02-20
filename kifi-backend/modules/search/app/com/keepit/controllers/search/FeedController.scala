package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.feed.FeedCommanderFactory
import play.api.mvc.Action
import play.api.libs.json.Json


class FeedController @Inject()(
  feedCommanderFactory: FeedCommanderFactory
) extends SearchServiceController {
  def getFeeds(userId: Id[User], limit: Int) = Action { implicit request =>
    val cmdr = feedCommanderFactory(userId)
    val feeds = cmdr.getFeeds(userId, limit)
    Ok(Json.toJson(feeds))
  }
}
