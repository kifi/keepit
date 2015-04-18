package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryCommander, UserCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.controllers.website.UserLibraryCountSortingHelper
import com.keepit.model.{ LibraryInfo, LibraryRepo, SocialUserInfoRepo }
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsNumber, Json }

class MobilePeopleRecommendationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    peopleRecoCommander: UserCommander,
    socialUserRepo: SocialUserInfoRepo,
    db: Database,
    libraryRepo: LibraryRepo,
    val libCommander: LibraryCommander,
    implicit val config: PublicIdConfiguration) extends UserActions with ShoeboxServiceController with UserLibraryCountSortingHelper {

  def getFriendRecommendations(offset: Int, limit: Int) = UserAction.async { request =>
    val viewer = request.userId
    peopleRecoCommander.getFriendRecommendations(viewer, offset, limit).map {
      case None => Ok(Json.obj("users" -> JsArray()))
      case Some(recoData) => {
        val recommendedUsers = sortUserByLibraryCount(recoData.recommendedUsers)
        val basicUsers = recoData.basicUsers
        val mutualFriends = recoData.mutualFriends
        val userConnectionCounts = recoData.userConnectionCounts
        val mutualLibraries = recoData.mutualLibraries

        val recommendedUsersArray = JsArray(recommendedUsers.map { recommendedUserId =>
          val mutualFriendsArray = JsArray(mutualFriends(recommendedUserId).toSeq.map { mutualFriendId =>
            BasicUser.format.writes(basicUsers(mutualFriendId)) + ("numFriends" -> JsNumber(userConnectionCounts(mutualFriendId)))
          })
          val mutualLibrariesArray = JsArray(
            mutualLibraries.get(recommendedUserId).map { mutualLibraries =>
              mutualLibraries.map { lib =>
                Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, None, basicUsers(lib.ownerId)))
              }
            } getOrElse Seq.empty
          )
          BasicUser.format.writes(basicUsers(recommendedUserId)) + ("numFriends" -> JsNumber(userConnectionCounts(recommendedUserId))) + ("mutualFriends" -> mutualFriendsArray) + ("mutualLibraries" -> mutualLibrariesArray)
        })
        val json = Json.obj("users" -> recommendedUsersArray)
        Ok(json)
      }
    }
  }
}

