package com.keepit.abook.commanders

import com.keepit.common.db.Id
import com.keepit.model.User
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.abook.model.FriendRecommendationRepo
import com.keepit.graph.GraphServiceClient
import scala.concurrent.Future
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class FriendRecommendationCommander @Inject() (
    db: Database,
    friendRecommendationRepo: FriendRecommendationRepo,
    graph: GraphServiceClient,
    shoebox: ShoeboxServiceClient) {

  def reportIrrelevantUserRecommendations(userId: Id[User], irrelevantUserIds: Seq[Id[User]]): Unit = {
    db.readWrite { implicit session =>
      irrelevantUserIds.foreach(friendRecommendationRepo.recordIrrelevantRecommendation(userId, _))
    }
  }

  def getRecommendedUsers(userId: Id[User], page: Int, pageSize: Int): Future[Seq[Id[User]]] = {
    val futureFriendships = graph.getUserFriendships(userId, bePatient = false)
    val futureFriends = shoebox.getFriends(userId)
    val futureFriendRequests = shoebox.getFriendRequestsBySender(userId)
    val rejectedRecommendations = db.readOnlyMaster { implicit session =>
      friendRecommendationRepo.getIrrelevantRecommendations(userId)
    }
    futureFriendships.flatMap { friendships =>
      if (friendships.isEmpty) Future.successful(Seq.empty)
      else for {
        friends <- futureFriends
        friendRequests <- futureFriendRequests
      } yield {
        val irrelevantRecommendations = rejectedRecommendations ++ friends ++ friendRequests.map(_.recipientId)
        val recommendations = friendships.iterator.filter { case (friendId, _) => !irrelevantRecommendations.contains(friendId) }
        recommendations.drop(page * pageSize).take(pageSize).map(_._1).toSeq
      }
    }
  }
}
