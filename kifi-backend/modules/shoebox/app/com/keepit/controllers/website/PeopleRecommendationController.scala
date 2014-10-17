package com.keepit.controllers.website

import com.keepit.commanders.{ UserCommander, InviteCommander, UserConnectionsCommander }
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController }
import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.model._
import play.api.libs.json.{ Json, JsNumber, JsArray }
import com.keepit.social.{ SocialNetworks, SocialNetworkType, BasicUser }
import com.keepit.common.db.ExternalId
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.slick.Database
import com.keepit.abook.model.InviteRecommendation
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.core._

class PeopleRecommendationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    abookServiceClient: ABookServiceClient,
    userConnectionRepo: UserConnectionRepo,
    basicUserRepo: BasicUserRepo,
    userRepo: UserRepo,
    db: Database,
    peopleRecoCommander: UserCommander,
    socialUserRepo: SocialUserInfoRepo,
    inviteCommander: InviteCommander) extends UserActions with ShoeboxServiceController {

  def getFriendRecommendations(offset: Int, limit: Int) = UserAction.async { request =>
    peopleRecoCommander.getFriendRecommendations(request.userId, offset, limit).map {
      case None => Ok(Json.obj("users" -> JsArray()))
      case Some(recoData) => {
        val recommendedUsers = recoData.recommendedUsers
        val basicUsers = recoData.basicUsers
        val mutualFriends = recoData.mutualFriends
        val mutualFriendConnectionCounts = recoData.mutualFriendConnectionCounts

        val recommendedUsersArray = JsArray(recommendedUsers.map { recommendedUserId =>
          val mutualFriendsArray = JsArray(mutualFriends(recommendedUserId).toSeq.map { mutualFriendId =>
            BasicUser.basicUserFormat.writes(basicUsers(mutualFriendId)) + ("numFriends" -> JsNumber(mutualFriendConnectionCounts(mutualFriendId)))
          })
          BasicUser.basicUserFormat.writes(basicUsers(recommendedUserId)) + ("mutualFriends" -> mutualFriendsArray)
        })
        val json = Json.obj("users" -> recommendedUsersArray)
        Ok(json)
      }
    }
  }

  def hideFriendRecommendation(id: ExternalId[User]) = UserAction.async { request =>
    val irrelevantUserId = db.readOnlyReplica { implicit session => userRepo.get(id).id.get }
    abookServiceClient.hideFriendRecommendation(request.userId, irrelevantUserId).map { _ =>
      Ok(Json.obj("hidden" -> true))
    }
  }

  def getInviteRecommendations(offset: Int, limit: Int) = UserAction.async { request =>
    val relevantNetworks = db.readOnlyReplica { implicit session =>
      socialUserRepo.getByUser(request.userId).map(_.networkType).toSet - SocialNetworks.FORTYTWO + SocialNetworks.EMAIL
    }
    val futureInviteRecommendations = {
      if (request.experiments.contains(ExperimentType.GRAPH_BASED_PEOPLE_TO_INVITE)) {
        abookServiceClient.getInviteRecommendations(request.userId, offset, limit, relevantNetworks)
      } else {
        inviteCommander.getInviteRecommendations(request.userId, offset / limit, limit)
      }
    }
    futureInviteRecommendations.imap(recommendations => Ok(Json.toJson(recommendations)))
  }

  def hideInviteRecommendation() = UserAction.async(parse.json) { request =>
    val network = (request.body \ "network").as[SocialNetworkType]
    val identifier = (request.body \ "identifier").as(InviteRecommendation.identifierFormat)
    val irrelevantFriendId = identifier.right.map { socialId =>
      val socialUserInfo = db.readOnlyReplica { implicit session => socialUserRepo.get(socialId, network) }
      socialUserInfo.id.get
    }
    abookServiceClient.hideInviteRecommendation(request.userId, network, irrelevantFriendId).map { _ =>
      Ok(Json.obj("hidden" -> true))
    }
  }
}
