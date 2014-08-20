package com.keepit.controllers.mobile

import com.keepit.commanders.PeopleRecommendationCommander
import com.keepit.common.controller.{ ActionAuthenticator, ShoeboxServiceController, WebsiteController }
import com.google.inject.Inject
import com.keepit.model.SocialUserInfoRepo
import play.api.libs.json.{ Json, JsNumber, JsArray }
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobilePeopleRecommendationController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    peopleRecoCommander: PeopleRecommendationCommander,
    socialUserRepo: SocialUserInfoRepo) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def getFriendRecommendations(page: Int, pageSize: Int, offset: Option[Int], limit: Option[Int]) = JsonAction.authenticatedAsync { request =>
    peopleRecoCommander.getFriendRecommendations(request.userId, offset.getOrElse(page * pageSize), limit.getOrElse(pageSize)).map {
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
}

