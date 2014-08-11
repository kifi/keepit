package com.keepit.abook.controllers

import com.google.inject.Inject
import com.keepit.common.controller.ABookServiceController
import play.api.libs.json.Json
import com.keepit.abook.commanders.ABookRecommendationCommander
import com.keepit.common.db.Id
import com.keepit.model.{ SocialUserInfo, User }
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.SocialNetworkType
import com.keepit.serializer.EitherFormat
import com.keepit.common.mail.EmailAddress

class ABookRecommendationController @Inject() (
    friendRecommendationCommander: ABookRecommendationCommander) extends ABookServiceController {

  def getFriendRecommendations(userId: Id[User], page: Int, pageSize: Int) = Action.async { request =>
    friendRecommendationCommander.getFriendRecommendations(userId, page, pageSize).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]) = Action { request =>
    friendRecommendationCommander.hideFriendRecommendation(userId, irrelevantUserId)
    Ok
  }

  def getInviteRecommendations(userId: Id[User], page: Int, pageSize: Int, networks: String) = Action.async { request =>
    val relevantNetworks = networks.split(",").map(SocialNetworkType(_)).toSet
    friendRecommendationCommander.getInviteRecommendations(userId, page, pageSize, relevantNetworks).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def hideInviteRecommendation(userId: Id[User]) = Action(parse.json) { request =>
    val network = (request.body \ "network").as[SocialNetworkType]
    val irrelevantFriendId = (request.body \ "irrelevantFriendId").as(EitherFormat[EmailAddress, Id[SocialUserInfo]])
    friendRecommendationCommander.hideInviteRecommendation(userId, network, irrelevantFriendId)
    Ok
  }
}
