package com.keepit.controllers.ext


import com.keepit.test.{SearchApplication, SearchApplicationInjector}
import org.specs2.mutable._

import com.keepit.model._
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.inject._
import com.keepit.common.time._
import com.keepit.common.controller.{FakeActionAuthenticator, FakeActionAuthenticatorModule}
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.google.inject.Injector

import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.{Json, JsObject}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import akka.actor.ActorSystem

import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.IdFilterCompressor


import com.keepit.search.index.{VolatileIndexDirectoryImpl, IndexDirectory, DefaultAnalyzer}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.user.UserIndexer
import com.keepit.search.user.{UserSearchFilterFactory, UserQueryParser}
import com.keepit.common.healthcheck.AirbrakeNotifier


class ExtUserSearchControllerTest extends Specification with SearchApplicationInjector {

   private def setup(client: FakeShoeboxServiceClientImpl) = {
    val extIds = (0 until 5).map{ i => "4e5f7b8c-951b-4497-8661-12345678900" + i.toString}.map{ExternalId[User]}
    val users = (0 until 4).map{ i =>
      User(externalId = extIds(i), firstName = s"firstName${i}", lastName = s"lastName${i}", pictureName = Some(s"picName${i}"))
    } :+ User(externalId = extIds(4), firstName = "Woody", lastName = "Allen", pictureName = Some("face"))

    val usersWithId = client.saveUsers(users: _*)

    val emails = (0 until 4).map{ i =>
      EmailAddress(userId = usersWithId(i).id.get, address = s"user${i}@42go.com")
    } ++ Seq(EmailAddress(userId = usersWithId(4).id.get, address = "woody@fox.com"),
     EmailAddress(userId = usersWithId(4).id.get, address = "Woody.Allen@GMAIL.com"))

    client.saveEmails(emails: _*)

    val friendRequests = Seq(FriendRequest(senderId = Id[User](1), recipientId = Id[User](2)))
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

  "ExtUserSearchController" should {
    "search user" in {
      running(new SearchApplication(modules:_*)) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = inject[UserIndexer]
        indexer.run(100, 100)

        val path = com.keepit.controllers.ext.routes.ExtUserSearchController.search("woody", None, None, 3).toString
        path === "/search/users/search?query=woody&maxHits=3"

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
                      "pictureName":"fake.jpg"
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
        indexer.run(100, 100)

        val path = com.keepit.controllers.ext.routes.ExtUserSearchController.page("firstNa", None, 0, 10).toString
        path === "/search/users/page?query=firstNa&pageNum=0&pageSize=10"

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
                "pictureName":"fake.jpg"
              },
              "status":"requested"
            },

            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789002",
                "firstName":"firstName2",
                "lastName":"lastName2",
                "pictureName":"fake.jpg"
              },
              "status":"friend"
            },

            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789003",
                "firstName":"firstName3",
                "lastName":"lastName3",
                "pictureName":"fake.jpg"
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
        indexer.run(100, 100)

        val path = com.keepit.controllers.ext.routes.ExtUserSearchController.page("woody@fox.com", None, 0, 10).toString
        path === "/search/users/page?query=woody%40fox.com&pageNum=0&pageSize=10"

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
                "pictureName":"fake.jpg"
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
