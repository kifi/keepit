package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryCommander, UserCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.controllers.website.UserLibraryCountSortingHelper
import com.keepit.model.SocialUserInfoRepo
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsNumber, Json }

class MobilePeopleRecommendationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    peopleRecoCommander: UserCommander,
    socialUserRepo: SocialUserInfoRepo,
    val libCommander: LibraryCommander) extends UserActions with ShoeboxServiceController with UserLibraryCountSortingHelper {

  def getFriendRecommendations(offset: Int, limit: Int) = UserAction.async { request =>
    peopleRecoCommander.getFriendRecommendations(request.userId, offset, limit).map {
      case None => Ok(Json.obj("users" -> JsArray()))
      case Some(recoData) => {
        val recommendedUsers = sortUserByLibraryCount(recoData.recommendedUsers)
        val basicUsers = recoData.basicUsers
        val mutualFriends = recoData.mutualFriends
        val mutualFriendConnectionCounts = recoData.mutualFriendConnectionCounts

        val recommendedUsersArray = JsArray(recommendedUsers.map { recommendedUserId =>
          val mutualFriendsArray = JsArray(mutualFriends(recommendedUserId).toSeq.map { mutualFriendId =>
            BasicUser.format.writes(basicUsers(mutualFriendId)) + ("numFriends" -> JsNumber(mutualFriendConnectionCounts(mutualFriendId)))
          })
          BasicUser.format.writes(basicUsers(recommendedUserId)) + ("mutualFriends" -> mutualFriendsArray)
        })
        val json = Json.obj("users" -> recommendedUsersArray)
        Ok(json)
      }
    }
  }
}

