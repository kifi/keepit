package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ ActionAuthenticator, MobileController, SearchServiceController }
import com.keepit.search.feed.FeedCommander
import com.keepit.search.feed.FeedResult

class MobileFeedController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    feedCommander: FeedCommander) extends MobileController(actionAuthenticator) with SearchServiceController {

  def pageV1(pageNum: Int, pageSize: Int) = JsonAction.authenticated { request =>
    val userId = request.userId
    val feeds = feedCommander.getFeeds(userId, (pageNum + 1) * pageSize).drop(pageNum * pageSize).take(pageSize)
    Ok(FeedResult.v1(feeds))
  }
}
