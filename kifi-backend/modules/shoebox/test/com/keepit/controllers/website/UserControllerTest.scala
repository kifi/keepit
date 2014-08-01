package com.keepit.controllers.website

import org.specs2.mutable.Specification

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.test.{ ShoeboxTestInjector, ShoeboxApplication }

import play.api.libs.json.{ Json }
import play.api.test.Helpers._
import play.api.test._
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ TestShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.scraper.{ TestScraperServiceClientModule, FakeScrapeSchedulerModule }

import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class UserControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    TestSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    TestABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule()
  )

  "UserController" should {

    "get currentUser" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          val user = inject[UserRepo].save(User(firstName = "Shanee", lastName = "Smith"))
          inject[UserExperimentRepo].save(UserExperiment(userId = user.id.get, experimentType = ExperimentType.ADMIN))
          user
        }

        val path = com.keepit.controllers.website.routes.UserController.currentUser().toString
        path === "/site/user/me"

        inject[FakeActionAuthenticator].setUser(user, Set(ExperimentType.ADMIN))

        val request = FakeRequest("GET", path)
        val result = inject[UserController].currentUser()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Shanee",
              "lastName":"Smith",
              "pictureName":"0.jpg",
              "emails":[],
              "notAuthed":[],
              "experiments":["admin"],
              "uniqueKeepsClicked":0,
              "totalKeepsClicked":0,
              "clickCount":0,
              "rekeepCount":0,
              "rekeepTotalCount":0
            }
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "basicUserInfo" should {
      "return user info when found" in {
        withDb(controllerTestModules: _*) { implicit injector =>

          val user = inject[Database].readWrite { implicit rw =>
            inject[UserRepo].save(User(firstName = "Donald", lastName = "Trump"))
          }

          inject[FakeActionAuthenticator].setUser(user)

          val controller = inject[UserController] // setup
          val result = controller.basicUserInfo(user.externalId, true)(FakeRequest())
          var body: String = contentAsString(result)

          contentType(result).get must beEqualTo("application/json")
          body must contain("id\":\"" + user.externalId)
          body must contain("firstName\":\"Donald")
          body must contain("lastName\":\"Trump")
          body must contain("friendCount\":0")
        }
      }
    }
  }
}
