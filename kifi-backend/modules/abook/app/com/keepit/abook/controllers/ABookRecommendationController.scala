package com.keepit.abook.controllers

import com.google.inject.Inject
import com.keepit.common.controller.ABookServiceController
import play.api.libs.json.Json
import com.keepit.abook.commanders.{ AbookOrganizationRecommendationCommander, ABookUserRecommendationCommander }
import com.keepit.common.db.Id
import com.keepit.model.{ Organization, SocialUserInfo, User }
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.SocialNetworkType
import com.keepit.common.mail.EmailAddress
import com.keepit.common.json.EitherFormat

class ABookRecommendationController @Inject() (
    abookUserRecommendationCommander: ABookUserRecommendationCommander,
    abookOrganizationRecommendationCommander: AbookOrganizationRecommendationCommander) extends ABookServiceController {

  def getFriendRecommendationsForUser(userId: Id[User], offset: Int, limit: Int, bePatient: Boolean = false) = Action.async { request =>
    abookUserRecommendationCommander.getUserRecommendations(userId, offset, limit, bePatient).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def getRecommendationsForOrg(orgId: Id[Organization], offset: Int, limit: Int, bePatient: Boolean = false) = Action.async(parse.json) { request =>
    val memberIds = (request.body \ "usersToFilterOnContacts").as[Set[Id[User]]]
    abookOrganizationRecommendationCommander.getRecommendations(orgId, memberIds, offset, limit).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def hideFriendRecommendationForUser(userId: Id[User], irrelevantUserId: Id[User]) = Action { request =>
    abookUserRecommendationCommander.hideFriendRecommendation(userId, irrelevantUserId)
    Ok
  }

  def hideUserRecommendationForOrg(orgId: Id[Organization], memberId: Id[User], irrelevantUserId: Id[User]) = Action { request =>
    abookOrganizationRecommendationCommander.hideUserRecommendation(orgId, memberId, irrelevantUserId)
    Ok
  }

  def getInviteRecommendationsForUser(userId: Id[User], offset: Int, limit: Int, networks: String) = Action.async { request =>
    val relevantNetworks = networks.split(",").map(SocialNetworkType(_)).toSet
    abookUserRecommendationCommander.getNonUserRecommendations(userId, offset, limit, relevantNetworks).map { recommendedUsers =>
      val json = Json.toJson(recommendedUsers)
      Ok(json)
    }
  }

  def hideInviteRecommendationForUser(userId: Id[User]) = Action(parse.json) { request =>
    val network = (request.body \ "network").as[SocialNetworkType]
    val irrelevantFriendId = (request.body \ "irrelevantFriendId").as(EitherFormat[EmailAddress, Id[SocialUserInfo]])
    abookUserRecommendationCommander.hideInviteRecommendation(userId, network, irrelevantFriendId)
    Ok
  }

  def hideNonUserRecommendationForOrg(orgId: Id[Organization], memberId: Id[User]) = Action(parse.json) { request =>
    val emailAddress = (request.body \ "irrelevantEmail").as[EmailAddress]
    abookOrganizationRecommendationCommander.hideEmailRecommendation(orgId, memberId, emailAddress)
    Ok
  }

  def getIrrelevantPeopleForUser(userId: Id[User]) = Action.async { request =>
    abookUserRecommendationCommander.getIrrelevantPeople(userId).map { irrelevantPeople =>
      Ok(Json.toJson(irrelevantPeople))
    }
  }

  def getIrrelevantPeopleForOrg(orgId: Id[Organization]) = Action.async { request =>
    abookOrganizationRecommendationCommander.getIrrelevantPeople(orgId).map { irrelevantPeople =>
      Ok(Json.toJson(irrelevantPeople))
    }
  }
}
