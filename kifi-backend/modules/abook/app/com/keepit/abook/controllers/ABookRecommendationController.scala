package com.keepit.abook.controllers

import com.google.inject.Inject
import com.keepit.common.controller.ABookServiceController
import play.api.libs.json.Json
import com.keepit.abook.commanders.ABookRecommendationCommander
import com.keepit.common.db.Id
import com.keepit.model.{ Organization, SocialUserInfo, User }
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.SocialNetworkType
import com.keepit.common.mail.EmailAddress
import com.keepit.common.json.EitherFormat

class ABookRecommendationController @Inject() (
    abookRecommendationCommander: ABookRecommendationCommander) extends ABookServiceController {

  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int, bePatient: Boolean = false) = Action.async { request =>
    abookRecommendationCommander.getFriendRecommendations(userId, offset, limit, bePatient).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]) = Action { request =>
    abookRecommendationCommander.hideFriendRecommendation(userId, irrelevantUserId)
    Ok
  }

  def getInviteRecommendations(userId: Id[User], offset: Int, limit: Int, networks: String) = Action.async { request =>
    val relevantNetworks = networks.split(",").map(SocialNetworkType(_)).toSet
    abookRecommendationCommander.getInviteRecommendations(userId, offset, limit, relevantNetworks).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def hideInviteRecommendation(userId: Id[User]) = Action(parse.json) { request =>
    val network = (request.body \ "network").as[SocialNetworkType]
    val irrelevantFriendId = (request.body \ "irrelevantFriendId").as(EitherFormat[EmailAddress, Id[SocialUserInfo]])
    abookRecommendationCommander.hideInviteRecommendation(userId, network, irrelevantFriendId)
    Ok
  }

  def getIrrelevantPeopleForUser(userId: Id[User]) = Action.async { request =>
    abookRecommendationCommander.getIrrelevantPeopleForUser(userId).map { irrelevantPeople =>
      Ok(Json.toJson(irrelevantPeople))
    }
  }

  def getIrrelevantPeopleForOrg(orgId: Id[Organization]) = Action.async { request =>
    abookRecommendationCommander.getIrrelevantPeopleForOrg(orgId).map { irrelevantPeople =>
      Ok(Json.toJson(irrelevantPeople))
    }
  }
}
