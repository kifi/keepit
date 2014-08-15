package com.keepit.commanders

import com.keepit.abook.{ FakeABookServiceClientModule, FakeABookServiceClientImpl, ABookServiceClient }
import com.keepit.model.{ UserConnection, User, UserRepo, UserConnectionRepo }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import concurrent.Await
import concurrent.duration.Duration

class PeopleRecommendationCommanderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule()
  )

  "PeopleRecommendationCommanderTest" should {
    "getFriendRecommendations" should {
      "return users and mutual users friend counts" in {
        withDb(modules: _*) { implicit injector =>
          val userConnRepo = inject[UserConnectionRepo]
          val userRepo = inject[UserRepo]
          val (user1, user2, user3, user4) = db.readWrite { implicit rw =>
            val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Paul"))
            val user2 = userRepo.save(User(firstName = "Bryan", lastName = "Cranston"))
            val user3 = userRepo.save(User(firstName = "Anna", lastName = "Gunn"))
            val user4 = userRepo.save(User(firstName = "Dean", lastName = "Norris"))

            userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user3.id.get))
            userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user4.id.get))
            userConnRepo.save(UserConnection(user1 = user3.id.get, user2 = user4.id.get))
            userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user2.id.get))
            userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user3.id.get))

            (user1, user2, user3, user4)
          }

          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          abook.addFriendRecommendationsExpectations(user1.id.get, Seq(user2.id.get, user3.id.get, user4.id.get))

          val friendRecoDataF = inject[PeopleRecommendationCommander].getFriendRecommendations(user1.id.get, 2, 25)
          val friendRecoData = Await.result(friendRecoDataF, Duration(5, "seconds"))

          friendRecoData.basicUsers === Map(
            user2.id.get -> com.keepit.social.BasicUser.fromUser(user2),
            user3.id.get -> com.keepit.social.BasicUser.fromUser(user3),
            user4.id.get -> com.keepit.social.BasicUser.fromUser(user4)
          )
          friendRecoData.recommendedUsers === Seq(user2.id.get, user3.id.get, user4.id.get)
          friendRecoData.mutualFriendConnectionCounts === Map(user2.id.get -> 3, user3.id.get -> 3)
          friendRecoData.mutualFriends === Map(
            user2.id.get -> Set(user3.id.get),
            user3.id.get -> Set(user2.id.get),
            user4.id.get -> Set(user2.id.get, user3.id.get)
          )
        }
      }
    }
  }
}
