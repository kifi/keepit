package com.keepit.commanders

import com.keepit.abook.{ FakeABookServiceClientModule, FakeABookServiceClientImpl, ABookServiceClient }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import concurrent.Await
import concurrent.duration.Duration

class PeopleRecommendationCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule()
  )

  "PeopleRecommendationCommander" should {
    "getFriendRecommendations" should {
      "return users and mutual users friend counts" in {
        withDb(modules: _*) { implicit injector =>
          val users = db.readWrite { implicit rw => testFactory.createUsersWithConnections() }
          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(users(0).id.get, Seq(users(1).id.get, users(2).id.get, users(3).id.get))

          val friendRecoDataF = inject[PeopleRecommendationCommander].getFriendRecommendations(users(0).id.get, 2, 25)
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
}
