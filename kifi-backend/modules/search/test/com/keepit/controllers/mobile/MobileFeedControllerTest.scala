package com.keepit.controllers.mobile

import com.keepit.test.{ SearchApplication, SearchApplicationInjector }
import org.specs2.mutable.Specification
import akka.actor.ActorSystem
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.search.feed.FixedResultFeedModule
import play.api.test.Helpers._
import play.api.libs.json._
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.controller.FakeActionAuthenticator

import play.api.test.FakeRequest

class MobileFeedControllerTest extends Specification with SearchApplicationInjector {

  val expected1 = Json.parse(s"""
    [
      {
        "title":"kifi",

        "url":"http://kifi.com",

        "sharingUsers":[
          {
            "id":"4e5f7b8c-951b-4497-8661-012345678901",
            "firstName":"u1",
            "lastName":"fake",
            "pictureName":"u1.png"
          }
        ],

        "firstKeptAt":"2014-01-30T21:59:00.000Z",

        "totalKeeperSize":10
      }
   ]
  """)

  val expected2 = Json.parse(s"""
     [
        {
          "title":"kifi",

          "url":"http://kifi.com",

          "sharingUsers":[
            {
              "id":"4e5f7b8c-951b-4497-8661-012345678901",
              "firstName":"u1",
              "lastName":"fake",
              "pictureName":"u1.png"
            }
          ],

          "firstKeptAt":"2014-01-30T21:59:00.000Z",

          "totalKeeperSize":10
        },


        {
          "title":"42go",

          "url":"http://42go.com",

          "sharingUsers":[

            {
              "id":"4e5f7b8c-951b-4497-8661-012345678901",
              "firstName":"u1",
              "lastName":"fake",
              "pictureName":"u1.png"
            },

            {
              "id":"4e5f7b8c-951b-4497-8661-012345678902",
              "firstName":"u2",
              "lastName":"fake",
              "pictureName":"u2.png"
            }

          ],

          "firstKeptAt":"2014-01-30T22:11:00.000Z",

          "totalKeeperSize":20
        }
     ]
   """)

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(
      StandaloneTestActorSystemModule(),
      FakeActionAuthenticatorModule(),
      FixedResultFeedModule()
    )
  }

  "mobileFeedController" should {
    "provide feeds with correct paging" in {

      running(new SearchApplication(modules: _*)) {
        val user = User(Some(Id[User](1)), firstName = "u1", lastName = "fake")
        inject[FakeActionAuthenticator].setUser(user)

        var path = com.keepit.controllers.mobile.routes.MobileFeedController.pageV1(0, 1).toString
        path === "/m/1/search/feeds/page?pageNum=0&pageSize=1"

        var request = FakeRequest("GET", path)
        var result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        Json.parse(contentAsString(result)) === expected1

        path = com.keepit.controllers.mobile.routes.MobileFeedController.pageV1(0, 2).toString
        path === "/m/1/search/feeds/page?pageNum=0&pageSize=2"

        request = FakeRequest("GET", path)
        result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        println(Json.parse(contentAsString(result)))

        Json.parse(contentAsString(result)) === expected2
      }
    }
  }
}
