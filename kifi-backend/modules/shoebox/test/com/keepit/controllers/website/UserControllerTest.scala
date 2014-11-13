package com.keepit.controllers.website

import com.keepit.curator.FakeCuratorServiceClientModule
import org.specs2.mutable.Specification

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.test.{ ShoeboxTestInjector, ShoeboxApplication }

import play.api.libs.json.{ JsArray, Json, JsNull }
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }

import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

import scala.concurrent.Future

class UserControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule()
  )

  "UserController" should {

    "get currentUser" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          val user = inject[UserRepo].save(User(firstName = "Shanee", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          inject[UserExperimentRepo].save(UserExperiment(userId = user.id.get, experimentType = ExperimentType.ADMIN))
          user
        }

        val path = routes.UserController.currentUser().url
        path === "/site/user/me"

        inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ADMIN))

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
              "username":"test", "active":true,
              "emails":[],
              "notAuthed":[],
              "experiments":["admin", "libraries"],
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

    "update username" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          val user = inject[UserRepo].save(User(firstName = "George", lastName = "Washington", username = Username("GeorgeWash"), normalizedUsername = "foo"))
          inject[UserExperimentRepo].save(UserExperiment(userId = user.id.get, experimentType = ExperimentType.ADMIN))
          user
        }
        val path = routes.UserController.updateUsername().url
        path === "/site/user/me/username"

        inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ADMIN))

        val inputJson1 = Json.obj(
          "username" -> "GDubs"
        )
        val request = FakeRequest("POST", path).withBody(inputJson1)
        val result: Future[Result] = inject[UserController].updateUsername()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse(s"""{"username":"GDubs"}""")
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "update user info" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GeorgeWash"), normalizedUsername = "foo"))
        }
        val userController = inject[UserController]
        val pathName = routes.UserController.updateName().url
        val pathDescription = routes.UserController.updateDescription().url
        pathName === "/site/user/me/name"
        pathDescription === "/site/user/me/description"

        inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ADMIN))

        val inputJson1 = Json.obj(
          "firstName" -> "Abe",
          "lastName" -> "Lincoln"
        )
        val request1 = FakeRequest("POST", pathName).withBody(inputJson1)
        val result1: Future[Result] = userController.updateName()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        db.readOnlyMaster { implicit s =>
          val userCheck = userRepo.get(user.id.get)
          userCheck.firstName === "Abe"
          userCheck.lastName === "Lincoln"
        }

        val inputJson2 = Json.obj(
          "description" -> "USA #1"
        )
        val request2 = FakeRequest("POST", pathDescription).withBody(inputJson2)
        val result2: Future[Result] = userController.updateDescription()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        db.readOnlyMaster { implicit s =>
          val userCheck = inject[UserValueRepo].getUserValue(user.id.get, UserValueName.USER_DESCRIPTION)
          userCheck.get.value === "USA #1"
        }
      }
    }

    "update user preferences" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GeorgeWash"), normalizedUsername = "foo"))
        }

        inject[FakeUserActionsHelper].setUser(user)
        val userController = inject[UserController]
        val path = routes.UserController.savePrefs().url

        val inputJson1 = Json.obj(
          "library_sorting_pref" -> "name",
          "show_delighted_question" -> false)
        val request1 = FakeRequest("POST", path).withBody(inputJson1)
        val result1: Future[Result] = userController.savePrefs()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        Json.parse(contentAsString(result1)) === inputJson1

        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.LIBRARY_SORTING_PREF) === Some("name")
        }

        val request2 = FakeRequest("GET", path)
        val result2: Future[Result] = userController.getPrefs()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) === Json.obj(
          "auto_show_guide" -> JsNull,
          "library_sorting_pref" -> "name",
          "show_delighted_question" -> false,
          "library_callout_shown" -> JsNull,
          "tag_callout_shown" -> JsNull,
          "guide_callout_shown" -> JsNull,
          "site_show_library_intro" -> JsNull)
      }
    }

    "handling emails" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Username("AbeLincoln"), normalizedUsername = "foo"))
        }
        val userController = inject[UserController]
        val userValueRepo = inject[UserValueRepo]
        val path = routes.UserController.addEmail().url
        path === "/site/user/me/email"

        val address1 = "vampireXslayer@gmail.com"
        val address2 = "uncleabe@gmail.com"

        val inputJson1 = Json.obj(
          "email" -> address1,
          "isPrimary" -> false
        )
        val inputJson2 = Json.obj(
          "email" -> address2,
          "isPrimary" -> true
        )

        inject[FakeUserActionsHelper].setUser(user)

        // add email1
        val request1 = FakeRequest("POST", path).withBody(inputJson1)
        val result1: Future[Result] = userController.addEmail()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        // add email2 as primary
        val request2 = FakeRequest("POST", path).withBody(inputJson2)
        val result2: Future[Result] = userController.addEmail()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        // add email2 again (but already added)
        val request3 = FakeRequest("POST", path).withBody(inputJson2)
        val result3: Future[Result] = userController.addEmail()(request3)
        status(result3) must equalTo(BAD_REQUEST)

        // verify emails
        db.readWrite { implicit session =>
          emailAddressRepo.getAllByUser(user.id.get).map { em =>
            emailAddressRepo.save(em.copy(state = UserEmailAddressStates.VERIFIED))
          }
          userRepo.save(user.copy(primaryEmail = Some(EmailAddress(address2)))) // because email2 is pending primary
          userValueRepo.clearValue(user.id.get, UserValueName.PENDING_PRIMARY_EMAIL)
        }

        // change primary to email1
        val request4 = FakeRequest("PUT", path).withBody(Json.obj("email" -> address1))
        val result4: Future[Result] = userController.changePrimaryEmail()(request4)
        status(result4) must equalTo(OK)
        contentType(result4) must beSome("application/json")

        // remove email2
        val request5 = FakeRequest("DELETE", path).withBody(Json.obj("email" -> address2))
        val result5: Future[Result] = userController.removeEmail()(request5)
        status(result5) must equalTo(OK)
        contentType(result5) must beSome("application/json")

        // remove email1 (but can't since it's primary)
        val request6 = FakeRequest("DELETE", path).withBody(Json.obj("email" -> address1))
        val result6: Future[Result] = userController.removeEmail()(request6)
        status(result6) must equalTo(BAD_REQUEST) // cannot delete primary email
      }
    }

    "basicUserInfo" should {
      "return user info when found" in {
        withDb(controllerTestModules: _*) { implicit injector =>

          val user = inject[Database].readWrite { implicit rw =>
            inject[UserRepo].save(User(firstName = "Donald", lastName = "Trump", username = Username("test"), normalizedUsername = "test"))
          }

          inject[FakeUserActionsHelper].setUser(user)

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
