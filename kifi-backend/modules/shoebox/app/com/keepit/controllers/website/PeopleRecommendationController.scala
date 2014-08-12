package com.keepit.controllers.website

import com.keepit.common.controller.{ ActionAuthenticator, ShoeboxServiceController, WebsiteController }
import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.model.{ UserRepo, UserConnectionRepo, User, SocialUserInfoRepo }
import play.api.libs.json.{ Json, JsNumber, JsArray }
import com.keepit.social.{ SocialNetworks, SocialNetworkType, BasicUser }
import com.keepit.common.db.ExternalId
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.slick.Database
import com.keepit.abook.model.InviteRecommendation
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class PeopleRecommendationController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    abookServiceClient: ABookServiceClient,
    userConnectionRepo: UserConnectionRepo,
    basicUserRepo: BasicUserRepo,
    userRepo: UserRepo,
    db: Database,
    socialUserRepo: SocialUserInfoRepo) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def getFriendRecommendations(page: Int, pageSize: Int) = JsonAction.authenticatedAsync { request =>
    abookServiceClient.getFriendRecommendations(request.userId, page, pageSize).map { recommendedUsers =>
      val friends = db.readOnlyReplica { implicit session =>
        (recommendedUsers.toSet + request.userId).map(id => id -> userConnectionRepo.getConnectedUsers(id)).toMap
      }
      val mutualFriends = recommendedUsers.map { recommendedUserId => recommendedUserId -> (friends(request.userId) intersect friends(recommendedUserId)) }.toMap
      val (basicUsers, mutualFriendConnectionCounts) = db.readOnlyReplica { implicit session =>
        val uniqueMutualFriends = mutualFriends.values.flatten.toSet
        val basicUsers = basicUserRepo.loadAll(uniqueMutualFriends ++ recommendedUsers)
        val mutualFriendConnectionCounts = uniqueMutualFriends.map { mutualFriendId => mutualFriendId -> userConnectionRepo.getConnectionCount(mutualFriendId) }.toMap
        (basicUsers, mutualFriendConnectionCounts)
      }
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

  def hideFriendRecommendation(id: ExternalId[User]) = JsonAction.authenticatedAsync { request =>
    val irrelevantUserId = db.readOnlyReplica { implicit session => userRepo.get(id).id.get }
    abookServiceClient.hideFriendRecommendation(request.userId, irrelevantUserId).map { _ =>
      Ok(Json.obj("hidden" -> true))
    }
  }

  def getInviteRecommendations(page: Int, pageSize: Int) = JsonAction.authenticatedAsync { request =>
    val relevantNetworks = db.readOnlyReplica { implicit session =>
      socialUserRepo.getByUser(request.userId).map(_.networkType).toSet - SocialNetworks.FORTYTWO + SocialNetworks.EMAIL
    }
    abookServiceClient.getInviteRecommendations(request.userId, page, pageSize, relevantNetworks).map { inviteRecommendations =>
      Ok(Json.toJson(inviteRecommendations))
    }
  }

  def hideInviteRecommendation() = JsonAction.authenticatedAsync(parse.json) { request =>
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
