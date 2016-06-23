package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.{ UserProfileCommander, UserCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.controllers.website.UserLibraryCountSortingHelper
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsObject, JsArray, JsNumber, Json }

class MobilePeopleRecommendationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    peopleRecoCommander: UserCommander,
    db: Database,
    userRepo: UserRepo,
    abookServiceClient: ABookServiceClient,
    val userProfileCommander: UserProfileCommander,
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
        val mutualLibrariesCounts = recoData.mutualLibrariesCounts

        val recommendedUsersArray = JsArray(recommendedUsers.map { recommendedUserId =>
          val mutualFriendsArray = JsArray(mutualFriends(recommendedUserId).toSeq.map { mutualFriendId =>
            BasicUser.format.writes(basicUsers(mutualFriendId)).as[JsObject] + ("numFriends" -> JsNumber(userConnectionCounts(mutualFriendId)))
          })
          val numUserConnections = userConnectionCounts.get(recommendedUserId).getOrElse(0)
          val numMutualLibraries = mutualLibrariesCounts.get(recommendedUserId).getOrElse(0)

          BasicUser.format.writes(basicUsers(recommendedUserId)).as[JsObject] + ("numFriends" -> JsNumber(numUserConnections)) + ("mutualFriends" -> mutualFriendsArray) + ("mutualLibraries" -> JsNumber(numMutualLibraries))
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
}

