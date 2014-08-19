package com.keepit.controllers.mobile

import com.keepit.abook.{ FakeABookServiceClientModule, FakeABookServiceClientImpl, ABookServiceClient }
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsArray, Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MobilePeopleRecommendationControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeABookServiceClientModule()
  )

  "MobilePeopleRecommendationController" should {

    "getFriendRecommendations" should {

      "return json for present recommendations" in {
        withDb(modules: _*) { implicit injector =>
          val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
          val users = db.readWrite { implicit rw => testFactory.createUsersWithConnections() }
          abook.addFriendRecommendationsExpectations(users(0).id.get, Seq(users(1).id.get, users(2).id.get, users(3).id.get))

          val controller = inject[MobilePeopleRecommendationController]
          val resultF = controller.getFriendRecommendations(1, 25, None, None)(FakeRequest())

          status(resultF) === 200
          contentType(resultF) must beSome("application/json")
          Json.parse(contentAsString(resultF)) === Json.parse(s"""
               |{"users":[
               |{"id":"${users(1).externalId}","firstName":"Bryan","lastName":"Cranston","pictureName":"0.jpg",
               |"mutualFriends":[{"id":"${users(2).externalId}","firstName":"Anna","lastName":"Gunn","pictureName":"0.jpg","numFriends":3}]},
               |{"id":"${users(2).externalId}","firstName":"Anna","lastName":"Gunn","pictureName":"0.jpg",
               |"mutualFriends":[{"id":"${users(1).externalId}","firstName":"Bryan","lastName":"Cranston","pictureName":"0.jpg","numFriends":3}]},
               |{"id":"${users(3).externalId}","firstName":"Dean","lastName":"Norris","pictureName":"0.jpg",
               |"mutualFriends":[{"id":"${users(1).externalId}","firstName":"Bryan","lastName":"Cranston","pictureName":"0.jpg","numFriends":3},
               |{"id":"${users(2).externalId}","firstName":"Anna","lastName":"Gunn","pictureName":"0.jpg","numFriends":3}]}
               |]}
             """.stripMargin)

          val call = com.keepit.controllers.mobile.routes.MobilePeopleRecommendationController.getFriendRecommendations()
          call.toString === "/m/1/user/friends/recommended"
          call.method === "GET"
        }
      }

    }

  }
}
