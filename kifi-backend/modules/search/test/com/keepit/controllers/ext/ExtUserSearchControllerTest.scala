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
    val users = (0 until 4).map{ i =>
      User(firstName = s"firstName${i}", lastName = s"lastName${i}", pictureName = Some(s"picName${i}"))
    } :+ User(externalId = ExternalId[User]("4e5f7b8c-951b-4497-8661-a1001885b2ec"), firstName = "Woody", lastName = "Allen", pictureName = Some("face"))

    val usersWithId = client.saveUsers(users: _*)

    val emails = (0 until 4).map{ i =>
      EmailAddress(userId = usersWithId(i).id.get, address = s"user${i}@42go.com")
    } ++ Seq(EmailAddress(userId = usersWithId(4).id.get, address = "woody@fox.com"),
     EmailAddress(userId = usersWithId(4).id.get, address = "Woody.Allen@GMAIL.com"))

    client.saveEmails(emails: _*)
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

        val controller = inject[ExtUserSearchController]
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
                      "id":"4e5f7b8c-951b-4497-8661-a1001885b2ec",
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
  }
}
