package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.model.{ UserConnectionRepo, User }
import com.keepit.social.BasicUser
import com.keepit.common.db.Id
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import concurrent.Future

case class FriendRecommendationData(basicUsers: Map[Id[User], BasicUser],
  mutualFriendConnectionCounts: Map[Id[User], Int],
  recommendedUsers: Seq[Id[User]],
  mutualFriends: Map[Id[User], Set[Id[User]]])

class PeopleRecommendationCommander @Inject() (
    abookServiceClient: ABookServiceClient,
    userConnectionRepo: UserConnectionRepo,
    basicUserRepo: BasicUserRepo,
    db: Database) {

  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int): Future[Option[FriendRecommendationData]] = {
    abookServiceClient.getFriendRecommendations(userId, offset, limit).map {
      _.map { recommendedUsers =>
        val friends = db.readOnlyReplica { implicit session =>
          (recommendedUsers.toSet + userId).map(id => id -> userConnectionRepo.getConnectedUsers(id)).toMap
        }

        val mutualFriends = recommendedUsers.map { recommendedUserId =>
          recommendedUserId -> (friends(userId) intersect friends(recommendedUserId))
        }.toMap

        val (basicUsers, mutualFriendConnectionCounts) = db.readOnlyReplica { implicit session =>
          val uniqueMutualFriends = mutualFriends.values.flatten.toSet
          val basicUsers = basicUserRepo.loadAll(uniqueMutualFriends ++ recommendedUsers)
          val mutualFriendConnectionCounts = uniqueMutualFriends.map { mutualFriendId => mutualFriendId -> userConnectionRepo.getConnectionCount(mutualFriendId) }.toMap
          (basicUsers, mutualFriendConnectionCounts)
        }

        FriendRecommendationData(basicUsers, mutualFriendConnectionCounts, recommendedUsers, mutualFriends)
      }
    }
  }

}
