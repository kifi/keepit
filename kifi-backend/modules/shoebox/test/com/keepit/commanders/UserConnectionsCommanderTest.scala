package com.keepit.commanders

import com.keepit.abook.{ FakeABookServiceClientModule, FakeABookServiceClientImpl, ABookServiceClient }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.graph.model.{ SociallyRelatedEntitiesForUser, RelatedEntities }
import com.keepit.graph.{ GraphServiceClient, FakeGraphServiceModule, FakeGraphServiceClientImpl }
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._

import concurrent.Await
import concurrent.duration.Duration

class UserConnectionsCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeABookServiceClientModule(),
    FakeGraphServiceModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule())

  "UserConnectionsCommander" should {

    // TODO: move this test case to UserCommanderTest
    "getFriendRecommendations" should {
      "return users and mutual users friend counts" in {
        withDb(modules: _*) { implicit injector =>
          val users = db.readWrite { implicit rw => testFactory.createUsersWithConnections() }
          val userId = users(0).id.get
          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(userId, Seq(users(1).id.get, users(2).id.get, users(3).id.get))

          val relatedEntities = {
            val relatedUsers = RelatedEntities[User, User](userId, Seq(users(2).id.get -> 10d, users(1).id.get -> 5d), totalSteps = 15)
            SociallyRelatedEntitiesForUser(relatedUsers, RelatedEntities.empty(userId), RelatedEntities.empty(userId), RelatedEntities.empty(userId), RelatedEntities.empty(userId), totalSteps = 100000)
          }
          inject[GraphServiceClient].asInstanceOf[FakeGraphServiceClientImpl].setSociallyRelatedEntitiesForUser(userId, relatedEntities)

          val friendRecoDataF = inject[UserCommander].getFriendRecommendations(userId, 2, 25)
          val friendRecoData = Await.result(friendRecoDataF, Duration(5, "seconds")).get

          friendRecoData.basicUsers === Map(
            users(1).id.get -> com.keepit.social.BasicUser.fromUser(users(1)),
            users(2).id.get -> com.keepit.social.BasicUser.fromUser(users(2)),
            users(3).id.get -> com.keepit.social.BasicUser.fromUser(users(3))
          )
          friendRecoData.recommendedUsers === Seq(users(1).id.get, users(2).id.get, users(3).id.get)
          friendRecoData.userConnectionCounts === Map(users(1).id.get -> 3, users(2).id.get -> 3, users(3).id.get -> 2)
          friendRecoData.mutualFriends === Map(
            users(1).id.get -> Seq(users(2).id.get),
            users(2).id.get -> Seq(users(1).id.get),
            users(3).id.get -> Seq(users(2).id.get, users(1).id.get)
          )
        }
      }
    }
  }

  "getMutualFriends" should {
    "return empty set if 2 users do not have mutual friends" in {
      withDb(modules: _*) { implicit injector =>
        val (user1: User, user2: User) = db.readWrite { implicit rw =>
          val saveUser = inject[UserRepo].save _
          (UserFactory.user().withName("first1", "last1").withUsername("test1").saved,
            UserFactory.user().withName("first3", "last2").withUsername("test2").saved)
        }

        val actual = inject[UserMutualConnectionsCommander].getMutualFriends(user1.id.get, user2.id.get)
        actual === Set.empty
      }
    }

    "return mutual friends of 2 users" in {
      withDb(modules: _*) { implicit injector =>
        val (user1: Id[User], user2: Id[User], commonUserIds) = db.readWrite { implicit rw =>
          val saveUser = inject[UserRepo].save _
          val users = for (i <- 0 to 9) yield UserFactory.user().withName(s"first$i", s"last$i").withUsername(s"test$i").saved

          val thisUserId = users(0).id.get
          val thatUserId = users(1).id.get
          val saveConn = inject[UserConnectionRepo].save _

          for (i <- 2 to 7) yield saveConn(UserConnection(user1 = thisUserId, user2 = users(i).id.get))
          for (i <- 5 to 9) yield saveConn(UserConnection(user1 = thatUserId, user2 = users(i).id.get))

          (thisUserId, thatUserId, users.drop(5).take(3).map(_.id.get))
        }

        val actual = inject[UserMutualConnectionsCommander].getMutualFriends(user1, user2)
        actual === commonUserIds.toSet
      }
    }
  }

  "friend" should {
    "connect two users if one ignores the other's request and then tries to connect with that same other user" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, frReqId) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved
          val frReq = inject[FriendRequestRepo].save(FriendRequest(senderId = user1.id.get, recipientId = user2.id.get, state = FriendRequestStates.IGNORED, messageHandle = None))
          (user1, user2, frReq.id.get)
        }

        inject[UserConnectionsCommander].friend(user2, user1.externalId) === (true, "acceptedRequest")

        db.readOnlyMaster { implicit s =>
          inject[FriendRequestRepo].get(frReqId).state === FriendRequestStates.ACCEPTED
          inject[UserConnectionRepo].areConnected(user1.id.get, user2.id.get) === true
        }
      }
    }
  }

}
