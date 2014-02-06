package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.search.graph.user._
import play.api.mvc.Action


class UserGraphController @Inject()(
  userGraph: UserGraphPlugin,
  searchFriendGraph: SearchFriendGraphPlugin
) extends SearchServiceController {

  def reindex() = Action { implicit request =>
    userGraph.reindex()
    searchFriendGraph.reindex()
    Ok("reindex userGraph and searchFriendGraph")
  }

  def updateUserGraph() = Action { implicit request =>
    userGraph.update()
    Ok("update userGraph")
  }

  def updateSearchFriends() = Action { implicit request =>
    searchFriendGraph.update()
    Ok("update searchFriendGraph")
  }
}
