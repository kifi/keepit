package com.keepit.controllers.website

import com.keepit.commanders.{ UserProfileCommander, UserCommander, InviteCommander }
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController }
import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.model._
import play.api.libs.json.{ JsObject, Json, JsNumber, JsArray }
import com.keepit.social.{ SocialNetworks, SocialNetworkType, BasicUser }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.slick.Database
import com.keepit.abook.model.UserInviteRecommendation
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.core._

class PeopleRecommendationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    abookServiceClient: ABookServiceClient,
    userRepo: UserRepo,
    db: Database,
    peopleRecoCommander: UserCommander,
    socialUserRepo: SocialUserInfoRepo,
    val userProfileCommander: UserProfileCommander) extends UserActions with ShoeboxServiceController with UserLibraryCountSortingHelper {

  def getFriendRecommendations(offset: Int, limit: Int) = UserAction.async { request =>
    peopleRecoCommander.getFriendRecommendations(request.userId, offset, limit).map {
      case None => Ok(Json.obj("users" -> JsArray()))
      case Some(recoData) => {
        val recommendedUsers = sortUserByLibraryCount(recoData.recommendedUsers)
        val basicUsers = recoData.basicUsers
        val mutualFriends = recoData.mutualFriends
        val mutualFriendConnectionCounts = recoData.userConnectionCounts

        val recommendedUsersArray = JsArray(recommendedUsers.map { recommendedUserId =>
          val mutualFriendsArray = JsArray(mutualFriends(recommendedUserId).toSeq.map { mutualFriendId =>
            BasicUser.format.writes(basicUsers(mutualFriendId)).as[JsObject] + ("numFriends" -> JsNumber(mutualFriendConnectionCounts(mutualFriendId)))
          })
          BasicUser.format.writes(basicUsers(recommendedUserId)).as[JsObject] + ("mutualFriends" -> mutualFriendsArray)
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
    val relevantNetworks = Set[SocialNetworkType](SocialNetworks.EMAIL) // Twitter, LinkedIn, Facebook have restricted our ability to invite
    /*    db.readOnlyReplica { implicit session =>
      socialUserRepo.getByUser(request.userId).map(_.networkType).toSet - SocialNetworks.FORTYTWO - SocialNetworks.LINKEDIN - SocialNetworks.TWITTER + SocialNetworks.EMAIL
    }*/
    val futureInviteRecommendations = {
      abookServiceClient.getInviteRecommendations(request.userId, offset, limit, relevantNetworks)
    }
    futureInviteRecommendations.imap(recommendations => Ok(Json.toJson(recommendations)))
  }

  def hideInviteRecommendation() = UserAction.async(parse.json) { request =>
    val network = (request.body \ "network").as[SocialNetworkType]
    val identifier = (request.body \ "identifier").as(UserInviteRecommendation.identifierFormat)
    val irrelevantFriendId = identifier.right.map { socialId =>
      val socialUserInfo = db.readOnlyReplica { implicit session => socialUserRepo.get(socialId, network) }
      socialUserInfo.id.get
    }
    abookServiceClient.hideInviteRecommendation(request.userId, network, irrelevantFriendId).map { _ =>
      Ok(Json.obj("hidden" -> true))
    }
  }
}

trait UserLibraryCountSortingHelper {
  val userProfileCommander: UserProfileCommander

  protected def sortUserByLibraryCount(users: Seq[Id[User]]): Seq[Id[User]] = {
    val libCnts = userProfileCommander.getOwnerLibraryCounts(users.toSet)
    val gpSize = 3
    if (libCnts.values.forall(_ == 0)) {
      users
    } else {
      // within each group, sort by library count (desc)
      users.sliding(gpSize, gpSize).toArray.map { subUsers => subUsers.toArray.sortBy(u => -1 * libCnts.getOrElse(u, 0)) }.flatten.toSeq
    }
  }
}
