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
class ABookRecommendationCommander @Inject() (
    db: Database,
    friendRecommendationRepo: FriendRecommendationRepo,
    graph: GraphServiceClient,
    shoebox: ShoeboxServiceClient) {

  def recordIrrelevantFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]): Unit = {
    db.readWrite { implicit session =>
      friendRecommendationRepo.recordIrrelevantRecommendation(userId, irrelevantUserId)
    }
  }

  def getFriendRecommendations(userId: Id[User], page: Int, pageSize: Int): Future[Seq[Id[User]]] = {
    val futureRelatedUsers = graph.getSociallyRelatedUsers(userId, bePatient = false)
    val futureFriends = shoebox.getFriends(userId)
    val futureFriendRequests = shoebox.getFriendRequestsBySender(userId)
    val futureFakeUsers = shoebox.getAllFakeUsers()
    val rejectedRecommendations = db.readOnlyMaster { implicit session =>
      friendRecommendationRepo.getIrrelevantRecommendations(userId)
    }
    futureRelatedUsers.flatMap { relatedUsersOption =>
      val relatedUsers = relatedUsersOption.map(_.related) getOrElse Seq.empty
      if (relatedUsers.isEmpty) Future.successful(Seq.empty)
      else for {
        friends <- futureFriends
        friendRequests <- futureFriendRequests
        fakeUsers <- futureFakeUsers
      } yield {
        val irrelevantRecommendations = rejectedRecommendations ++ friends ++ friendRequests.map(_.recipientId) ++ fakeUsers + userId
        val recommendations = relatedUsers.iterator.filter { case (friendId, _) => !irrelevantRecommendations.contains(friendId) }
        recommendations.drop(page * pageSize).take(pageSize).map(_._1).toSeq
      }
    }
  }
}
