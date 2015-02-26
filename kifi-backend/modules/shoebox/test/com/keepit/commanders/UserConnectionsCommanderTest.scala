package com.keepit.commanders

import com.keepit.abook.model.EmailAccountInfo
import com.keepit.abook.{ FakeABookServiceClientModule, FakeABookServiceClientImpl, ABookServiceClient }
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.graph.model.{ RelatedEntities, SociallyRelatedEntities }
import com.keepit.graph.{ GraphServiceClient, FakeGraphServiceModule, FakeGraphServiceClientImpl }
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserConnectionFactory._

import concurrent.Await
import concurrent.duration.Duration

class UserConnectionsCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeGraphServiceModule(),
    FakeSocialGraphModule()
  )

  "PeopleRecommendationCommander" should {
    "getFriendRecommendations" should {
      "return users and mutual users friend counts" in {
        withDb(modules: _*) { implicit injector =>
          val users = db.readWrite { implicit rw => testFactory.createUsersWithConnections() }
          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(users(0).id.get, Seq(users(1).id.get, users(2).id.get, users(3).id.get))

          val friendRecoDataF = inject[UserCommander].getFriendRecommendations(users(0).id.get, 2, 25)
          val friendRecoData = Await.result(friendRecoDataF, Duration(5, "seconds")).get

          friendRecoData.basicUsers === Map(
            users(1).id.get -> com.keepit.social.BasicUser.fromUser(users(1)),
            users(2).id.get -> com.keepit.social.BasicUser.fromUser(users(2)),
            users(3).id.get -> com.keepit.social.BasicUser.fromUser(users(3))
          )
          friendRecoData.recommendedUsers === Seq(users(1).id.get, users(2).id.get, users(3).id.get)
          friendRecoData.mutualFriendConnectionCounts === Map(users(1).id.get -> 3, users(2).id.get -> 3)
          friendRecoData.mutualFriends === Map(
            users(1).id.get -> Set(users(2).id.get),
            users(2).id.get -> Set(users(1).id.get),
            users(3).id.get -> Set(users(1).id.get, users(2).id.get)
          )
        }
      }
    }
  }

  "getConnectionsSortedByRelationship" in {
    withDb(modules: _*) { implicit injector =>
      val (owner, viewer, user1, user2, user3, user4, user5, user6, user7) = db.readWrite { implicit s =>
        val user1 = user().saved
        val user2 = user().saved
        val user3 = user().saved
        val user4 = user().saved
        val user5 = user().saved
        val user6 = user().saved
        val user7 = user().saved

        val owner = user().saved
        val viewer = user().saved

        connect(viewer -> user1).saved //mc
        connect(viewer -> user4).saved //mc - 0.1
        connect(viewer -> user5).saved
        connect(viewer -> user6).saved //mc

        connect(owner -> user1).saved //mc
        connect(owner -> user2).saved //   - 0.2
        connect(owner -> user3).saved //   - 0.3
        connect(owner -> user4).saved //mc - 0.1
        connect(owner -> user6).saved //mc
        connect(owner -> user7).saved
        (owner, viewer, user1, user2, user3, user4, user5, user6, user7)
      }

      val relationship = SociallyRelatedEntities(
        RelatedEntities[User, User](owner.id.get, Seq(user4.id.get -> .1, user5.id.get -> .4, user2.id.get -> .2, user3.id.get -> .3)),
        RelatedEntities[User, SocialUserInfo](owner.id.get, Seq.empty),
        RelatedEntities[User, SocialUserInfo](owner.id.get, Seq.empty),
        RelatedEntities[User, EmailAccountInfo](owner.id.get, Seq.empty)
      )

      Await.result(inject[GraphServiceClient].getSociallyRelatedEntities(viewer.id.get), Duration.Inf) === None
      inject[FakeGraphServiceClientImpl].setSociallyRelatedEntities(viewer.id.get, relationship)
      Await.result(inject[FakeGraphServiceClientImpl].getSociallyRelatedEntities(viewer.id.get), Duration.Inf).get === relationship
      Await.result(inject[GraphServiceClient].getSociallyRelatedEntities(viewer.id.get), Duration.Inf).get === relationship
      val commander = inject[UserConnectionsCommander]
      val connections = Await.result(commander.getConnectionsSortedByRelationship(viewer.id.get, owner.id.get), Duration.Inf)

      connections === Seq(user4, user1, user6, user3, user2, user7).map(_.id.get)
    }
  }

  "getMutualFriends" should {
    "return empty set if 2 users do not have mutual friends" in {
      withDb(modules: _*) { implicit injector =>
        val (user1: User, user2: User) = db.readWrite { implicit rw =>
          val saveUser = inject[UserRepo].save _
          (saveUser(User(firstName = s"first1", lastName = s"last1", username = Username("test"), normalizedUsername = "test")),
            saveUser(User(firstName = s"first1", lastName = s"last2", username = Username("test"), normalizedUsername = "test")))
        }

        val actual = inject[UserConnectionsCommander].getMutualFriends(user1.id.get, user2.id.get)
        actual === Set.empty
      }
    }

    "return mutual friends of 2 users" in {
      withDb(modules: _*) { implicit injector =>
        val (user1: Id[User], user2: Id[User], commonUserIds) = db.readWrite { implicit rw =>
          val saveUser = inject[UserRepo].save _
          val users = for (i <- 0 to 9) yield saveUser(User(firstName = s"first$i", lastName = s"last$i", username = Username("test"), normalizedUsername = "test"))

          val thisUserId = users(0).id.get
          val thatUserId = users(1).id.get
          val saveConn = inject[UserConnectionRepo].save _

          for (i <- 2 to 7) yield saveConn(UserConnection(user1 = thisUserId, user2 = users(i).id.get))
          for (i <- 5 to 9) yield saveConn(UserConnection(user1 = thatUserId, user2 = users(i).id.get))

          (thisUserId, thatUserId, users.drop(5).take(3).map(_.id.get))
        }

        val actual = inject[UserConnectionsCommander].getMutualFriends(user1, user2)
        actual === commonUserIds.toSet
      }
    }
  }

}
