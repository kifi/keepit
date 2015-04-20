package com.keepit.controllers.mobile

import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.{ Library, LibraryAccess, Username, User }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._

class MobilePeopleRecommendationControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeSocialGraphModule()
  )

  "MobilePeopleRecommendationController" should {

    "getFriendRecommendations" should {

      "return json for present recommendations" in {
        withDb(modules: _*) { implicit injector =>
          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          val (user1, user2, user3, user4, lib) = db.readWrite { implicit rw =>
            val users = testFactory.createUsersWithConnections()

            // create library followings
            val user1 = users(0)
            val user2 = users(1)
            val user3 = users(2)
            val user4 = users(3)
            val lib = library().withUser(user4).saved
            membership().withLibraryFollower(lib.id.get, user1.id.get).saved
            membership().withLibraryFollower(lib.id.get, user2.id.get).saved
            membership().withLibraryFollower(lib.id.get, user3.id.get).saved
            libraryMembershipRepo.countWithLibraryIdAndAccess(lib.id.get, LibraryAccess.READ_ONLY) === 3

            libraryRepo.getMutualLibrariesForUser(user1.id.get, user2.id.get).length === 1
            libraryRepo.getMutualLibrariesForUser(user1.id.get, user3.id.get).length === 1
            libraryRepo.getMutualLibrariesForUser(user1.id.get, user4.id.get).length === 0

            (user1, user2, user3, user4, lib)
          }
          val pubLibId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])
          abook.addFriendRecommendationsExpectations(user1.id.get, Seq(user2.id.get, user3.id.get, user4.id.get))

          inject[FakeUserActionsHelper].setUser(User(id = Some(Id[User](1L)), firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test"))
          val controller = inject[MobilePeopleRecommendationController]
          val resultF = controller.getFriendRecommendations(1, 10)(FakeRequest())

          status(resultF) === 200
          contentType(resultF) must beSome("application/json")
          Json.parse(contentAsString(resultF)) === Json.parse(s"""
             {"users":[
               {
                "id":"${user2.externalId}","firstName":"Bryan","lastName":"Cranston","pictureName":"0.jpg","username":"test2","numFriends": 3,
                "mutualFriends":[
                  {
                    "id":"${user3.externalId}","firstName":"Anna","lastName":"Gunn","username":"test3","pictureName":"0.jpg","numFriends":3
                  }],
                "mutualLibraries":1
                },
               {
                "id":"${user3.externalId}","firstName":"Anna","lastName":"Gunn","pictureName":"0.jpg","username":"test3","numFriends": 3,
                "mutualFriends":[
                  {
                    "id":"${user2.externalId}","firstName":"Bryan","lastName":"Cranston","username":"test2","pictureName":"0.jpg","numFriends":3
                  }],
                  "mutualLibraries":1
                },
               {
                "id":"${user4.externalId}","firstName":"Dean","lastName":"Norris","pictureName":"0.jpg","username":"test4","numFriends": 2,
                "mutualFriends":[
                  {
                    "id":"${user2.externalId}","firstName":"Bryan","lastName":"Cranston","pictureName":"0.jpg","username":"test2","numFriends":3
                  },
                  {
                    "id":"${user3.externalId}","firstName":"Anna","lastName":"Gunn","pictureName":"0.jpg","username":"test3","numFriends":3
                  }],
                "mutualLibraries":0
                }
             ]}
             """)

          val call = com.keepit.controllers.mobile.routes.MobilePeopleRecommendationController.getFriendRecommendations()
          call.toString === "/m/1/user/friends/recommended"
          call.method === "GET"
        }
      }

    }

  }
}
