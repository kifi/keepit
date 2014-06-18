package com.keepit.controllers.mobile

import com.keepit.test.{SearchApplication, SearchApplicationInjector}
import org.specs2.mutable._

import com.keepit.model._
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.controller.{FakeActionAuthenticator, FakeActionAuthenticatorModule}
import com.keepit.common.actor.StandaloneTestActorSystemModule

import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.Json

import akka.actor.ActorSystem

import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.FakeShoeboxServiceModule

import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.user.UserIndexer
import com.keepit.search.user.UserSearchFilterFactory
import com.keepit.common.mail.EmailAddress

class MobileUserSearchControllerTest extends Specification with SearchApplicationInjector {

  private def setup(client: FakeShoeboxServiceClientImpl) = {
    val extIds = (0 until 5).map{ i => "4e5f7b8c-951b-4497-8661-12345678900" + i.toString}.map{ExternalId[User]}
    val users = (0 until 4).map{ i =>
      User(externalId = extIds(i), firstName = s"firstName${i}", lastName = s"lastName${i}", pictureName = Some(s"picName${i}"))
    } :+ User(externalId = extIds(4), firstName = "Woody", lastName = "Allen", pictureName = Some("face"))

    val usersWithId = client.saveUsers(users: _*)

    val emails = (0 until 4).map { i => usersWithId(i).id.get -> EmailAddress(s"user${i}@42go.com") } ++ Seq(
      usersWithId(4).id.get -> EmailAddress("woody@fox.com"),
      usersWithId(4).id.get -> EmailAddress("Woody.Allen@GMAIL.com")
    )

    client.addEmails(emails: _*)

    val friendRequests = Seq(FriendRequest(senderId = Id[User](1), recipientId = Id[User](2), messageHandle = None))
    client.saveFriendRequests(friendRequests: _*)

    val connections = Map(Id[User](1) -> Set(Id[User](3)))
    client.saveConnections(connections)

    usersWithId
  }

  def filterFactory = inject[UserSearchFilterFactory]

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(
      StandaloneTestActorSystemModule(),
      FakeActionAuthenticatorModule(),
      FakeShoeboxServiceModule()
    )
  }

  "MobileUserSearchController" should {
    "search user" in {
      running(new SearchApplication(modules:_*)) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = inject[UserIndexer]
        indexer.update()

        val path = com.keepit.controllers.mobile.routes.MobileUserSearchController.searchV1("woody", None, None, 3).toString
        path === "/m/1/search/users/search?query=woody&maxHits=3"

        inject[FakeActionAuthenticator].setUser(users(0))
        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          {
            "hits":
              [
                {
                  "userId":5,
                  "basicUser":
                    {
                      "id":"4e5f7b8c-951b-4497-8661-123456789004",
                      "firstName":"Woody",
                      "lastName":"Allen",
                      "pictureName":"face.jpg"
                    },
                  "isFriend":false
                }
              ],
            "context":"AgAJAAcBBQ=="
            }
          """)
        Json.parse(contentAsString(result)) === expected
      }
    }

    "page user by name" in {
      running(new SearchApplication(modules:_*)) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = inject[UserIndexer]
        indexer.update()

        val path = com.keepit.controllers.mobile.routes.MobileUserSearchController.pageV1("firstNa", None, 0, 10).toString
        path === "/m/1/search/users/page?query=firstNa&pageNum=0&pageSize=10"

        inject[FakeActionAuthenticator].setUser(users(0))
        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          [
            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789001",
                "firstName":"firstName1",
                "lastName":"lastName1",
                "pictureName":"picName1.jpg"
              },
              "status":"requested"
            },

            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789002",
                "firstName":"firstName2",
                "lastName":"lastName2",
                "pictureName":"picName2.jpg"
              },
              "status":"friend"
            },
            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789003",
                "firstName":"firstName3",
                "lastName":"lastName3",
                "pictureName":"picName3.jpg"
              },
              "status":""
            }
          ]
          """)
        Json.parse(contentAsString(result)) === expected
      }
    }

    "page user by email" in {
      running(new SearchApplication(modules:_*)) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = inject[UserIndexer]
        indexer.update()

        val path = com.keepit.controllers.mobile.routes.MobileUserSearchController.pageV1("woody@fox.com", None, 0, 10).toString
        path === "/m/1/search/users/page?query=woody%40fox.com&pageNum=0&pageSize=10"

        inject[FakeActionAuthenticator].setUser(users(0))
        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          [
            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789004",
                "firstName":"Woody",
                "lastName":"Allen",
                "pictureName":"face.jpg"
              },
              "status":""
            }
          ]
         """)
         Json.parse(contentAsString(result)) === expected
      }
    }
  }
}
