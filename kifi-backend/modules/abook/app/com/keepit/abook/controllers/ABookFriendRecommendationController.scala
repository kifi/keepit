package com.keepit.abook.controllers

import com.google.inject.Inject
import com.keepit.common.controller.ABookServiceController
import play.api.libs.json.Json
import com.keepit.abook.commanders.FriendRecommendationCommander
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class ABookFriendRecommendationController @Inject() (
    friendRecommendationCommander: FriendRecommendationCommander) extends ABookServiceController {

  def getRecommendedUsers(userId: Id[User], page: Int, pageSize: Int) = Action.async { request =>
    friendRecommendationCommander.getRecommendedUsers(userId, page, pageSize).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def reportIrrelevantUserRecommendations(userId: Id[User]) = Action(parse.json) { request =>
    val irrelevantUsers = request.body.as[Seq[Id[User]]]
    friendRecommendationCommander.reportIrrelevantUserRecommendations(userId, irrelevantUsers)
    Ok
  }
}
