package com.keepit.controllers.mobile

import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.{ Username, User }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.scraper.FakeScrapeSchedulerModule

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
          val users = db.readWrite { implicit rw => testFactory.createUsersWithConnections() }
          abook.addFriendRecommendationsExpectations(users(0).id.get, Seq(users(1).id.get, users(2).id.get, users(3).id.get))

          inject[FakeUserActionsHelper].setUser(User(id = Some(Id[User](1L)), firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test"))
          val controller = inject[MobilePeopleRecommendationController]
          val resultF = controller.getFriendRecommendations(1, 25)(FakeRequest())

          status(resultF) === 200
          contentType(resultF) must beSome("application/json")
          Json.parse(contentAsString(resultF)) === Json.parse(s"""
             {"users":[
               {
                "id":"${users(1).externalId}","firstName":"Bryan","lastName":"Cranston","pictureName":"0.jpg","username":"test2","numFriends": 3,
                "mutualFriends":[
                  {
                    "id":"${users(2).externalId}","firstName":"Anna","lastName":"Gunn","username":"test3","pictureName":"0.jpg","numFriends":3
                  }
                ]},
               {
                "id":"${users(2).externalId}","firstName":"Anna","lastName":"Gunn","pictureName":"0.jpg","username":"test3","numFriends": 3,
                "mutualFriends":[
                  {
                    "id":"${users(1).externalId}","firstName":"Bryan","lastName":"Cranston","username":"test2","pictureName":"0.jpg","numFriends":3
                  }
                ]},
               {
                "id":"${users(3).externalId}","firstName":"Dean","lastName":"Norris","pictureName":"0.jpg","username":"test4","numFriends": 2,
                "mutualFriends":[
                  {
                    "id":"${users(1).externalId}","firstName":"Bryan","lastName":"Cranston","pictureName":"0.jpg","username":"test2","numFriends":3
                  },
                  {
                    "id":"${users(2).externalId}","firstName":"Anna","lastName":"Gunn","pictureName":"0.jpg","username":"test3","numFriends":3
                  }]
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
