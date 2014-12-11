package com.keepit.controllers.mobile

import com.keepit.common.net.FakeHttpClientModule
import com.keepit.test.{ SearchApplication, SearchTestInjector }
import org.specs2.mutable._

import com.keepit.model._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.controller.{ FakeSecureSocialClientIdModule, FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.actor.FakeActorSystemModule

import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.Json

import akka.actor.ActorSystem

import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.FakeShoeboxServiceModule

import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index.user.UserIndexer
import com.keepit.search.index.user.UserSearchFilterFactory
import com.keepit.common.mail.EmailAddress
import com.keepit.common.util.PlayAppConfigurationModule

import com.google.inject.Injector

class MobileUserSearchControllerTest extends Specification with SearchTestInjector {

  private def setup(client: FakeShoeboxServiceClientImpl) = {
    val extIds = (0 until 5).map { i => "4e5f7b8c-951b-4497-8661-12345678900" + i.toString }.map { ExternalId[User] }
    val users = (0 until 4).map { i =>
      User(externalId = extIds(i), firstName = s"firstName${i}", lastName = s"lastName${i}", pictureName = Some(s"picName${i}"), username = Username("test"), normalizedUsername = "test")
    } :+ User(externalId = extIds(4), firstName = "Woody", lastName = "Allen", pictureName = Some("face"), username = Username("test"), normalizedUsername = "test")

    val usersWithId = client.saveUsers(users: _*)

    val emails = (0 until 4).map { i => usersWithId(i).id.get -> EmailAddress(s"user${i}@42go.com") } ++ Seq(
      usersWithId(4).id.get -> EmailAddress("woody@fox.com"),
      usersWithId(4).id.get -> EmailAddress("Woody.Allen@GMAIL.com")
    )

    client.addEmails(emails: _*)

    val friendRequests = Seq(Id[User](1) -> Id[User](2))
    client.saveFriendRequests(friendRequests: _*)

    val connections = Map(Id[User](1) -> Set(Id[User](3)))
    client.saveConnections(connections)

    usersWithId
  }

  def filterFactory(implicit injector: Injector) = inject[UserSearchFilterFactory]

  def modules = {
    Seq(
      FakeActorSystemModule(),
      FakeUserActionsModule(),
      FakeHttpClientModule(),
      FakeSecureSocialClientIdModule(),
      FakeShoeboxServiceModule(),
      PlayAppConfigurationModule()
    )
  }

  "MobileUserSearchController" should {
    "search user" in {
      withInjector(modules: _*) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = inject[UserIndexer]
        indexer.update()

        val path = com.keepit.controllers.mobile.routes.MobileUserSearchController.searchV1("woody", None, None, 3).toString
        path === "/m/1/search/users/search?query=woody&maxHits=3"

        inject[FakeUserActionsHelper].setUser(users.head)
        val request = FakeRequest("GET", path)

        val controller = inject[MobileUserSearchController]
        val result = controller.searchV1("woody", None, None, 3)(request)
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
                      "pictureName":"face.jpg","username":"test"
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
      withInjector(modules: _*) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = inject[UserIndexer]
        indexer.update()

        val path = com.keepit.controllers.mobile.routes.MobileUserSearchController.pageV1("firstNa", None, 0, 10).toString
        path === "/m/1/search/users/page?query=firstNa&pageNum=0&pageSize=10"

        inject[FakeUserActionsHelper].setUser(users.head)
        val request = FakeRequest("GET", path)
        val controller = inject[MobileUserSearchController]
        val result = controller.pageV1("firstNa", None, 0, 10)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          [
            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789001",
                "firstName":"firstName1",
                "lastName":"lastName1",
                "pictureName":"picName1.jpg","username":"test"
              },
              "status":"requested"
            },

            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789002",
                "firstName":"firstName2",
                "lastName":"lastName2",
                "pictureName":"picName2.jpg","username":"test"
              },
              "status":"friend"
            },
            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789003",
                "firstName":"firstName3",
                "lastName":"lastName3",
                "pictureName":"picName3.jpg","username":"test"
              },
              "status":""
            }
          ]
          """)
        Json.parse(contentAsString(result)) === expected
      }
    }

    "page user by email" in {
      withInjector(modules: _*) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = inject[UserIndexer]
        indexer.update()

        val path = com.keepit.controllers.mobile.routes.MobileUserSearchController.pageV1("woody@fox.com", None, 0, 10).toString
        path === "/m/1/search/users/page?query=woody%40fox.com&pageNum=0&pageSize=10"

        inject[FakeUserActionsHelper].setUser(users.head)
        val request = FakeRequest("GET", path)
        val controller = inject[MobileUserSearchController]
        val result = controller.pageV1("woody@fox.com", None, 0, 10)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          [
            {
              "user":{
                "id":"4e5f7b8c-951b-4497-8661-123456789004",
                "firstName":"Woody",
                "lastName":"Allen",
                "pictureName":"face.jpg","username":"test"
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
