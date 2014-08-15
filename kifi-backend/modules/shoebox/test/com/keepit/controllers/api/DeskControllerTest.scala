package com.keepit.controllers.api

import com.keepit.common.controller.FakeActionAuthenticator
import com.keepit.model._
import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._

class DeskControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq()

  "DeskController" should {
    "is logged in" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val path = com.keepit.controllers.api.routes.DeskController.isLoggedIn.toString
        path === "/api/desk/isLoggedIn"

        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest()
        val result = inject[DeskController].isLoggedIn()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        Json.parse(contentAsString(result)) must equalTo(Json.parse("""{"loggedIn":true,"firstName":"Shanee","lastName":"Smith"}"""))
      }
    }
  }
}
