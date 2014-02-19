package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.search.SearchServiceClient
import com.keepit.common.db.Id
import com.keepit.model.User

import scala.concurrent.Await
import scala.concurrent.duration._
import views.html


class AdminFeedController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  searchClient: SearchServiceClient
) extends AdminController(actionAuthenticator) {

  def getFeeds(userId: Id[User], limit: Int) = AdminHtmlAction.authenticated { implicit request =>
    val start = System.currentTimeMillis()
    val feeds = Await.result(searchClient.getFeeds(userId, limit), 5 seconds)
    val elapsedSeconds = (System.currentTimeMillis() - start)/1000f
    Ok(html.admin.feeds(userId, limit, feeds, elapsedSeconds))
  }
}
