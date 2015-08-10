package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.{ FakeUserActionsHelper, ShoeboxServiceController }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsString, Json }
import play.api.mvc.{ Call, _ }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Await
import scala.concurrent.duration._

class UserOrOrganizationControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call): FakeRequest[AnyContentAsEmpty.type] = FakeRequest(route.method, route.url)

  val modules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  object TestData extends ShoeboxServiceController {
    val test1Body = Json.obj("hello" -> "world")
    val test1 = Ok(test1Body)

    val test2Body = JsString("foo")
    val test2 = Ok("foo")

    val test3Body = Json.obj("name" -> "Léo")
    val test3 = Ok(test3Body)

    val test4Body = Json.obj("name" -> "ĄąĲĳ	ɖ	ɞ	ɫ	ɷ	ʱ	ʬ	˕	˨ 	ਠਂ	ਅ	ਉ	ਠੱ")
    val test4 = Ok(test4Body)
  }

  "UserOrOrganizationController" should {
    "be able to extract the body from results" in {
      "succeed on an easy json object" in {
        withInjector(modules: _*) { implicit injector =>
          val result = TestData.test1
          val extractedBody = Await.result(controller.extractBody(result), Duration(1, SECONDS))
          extractedBody.isSuccess === true
          extractedBody.get === TestData.test1Body
        }
      }
      "fail gracefully on non-json string" in {
        withInjector(modules: _*) { implicit injector =>
          val result = TestData.test2
          val extractedBody = Await.result(controller.extractBody(result), Duration(1, SECONDS))
          extractedBody.isFailure === true
        }
      }
      "succeed on unicode characters" in {
        withInjector(modules: _*) { implicit injector =>
          val result = TestData.test3
          val extractedBody = Await.result(controller.extractBody(result), Duration(1, SECONDS))
          extractedBody.isSuccess === true
          extractedBody.get === TestData.test3Body
        }
      }
      "succeed on super weird unicode characters" in {
        withInjector(modules: _*) { implicit injector =>
          val result = TestData.test4
          val extractedBody = Await.result(controller.extractBody(result), Duration(1, SECONDS))
          extractedBody.isSuccess === true
          extractedBody.get === TestData.test4Body
        }
      }
    }

    "get user/orgs by handle" in {
      def testSetup(implicit injector: Injector) = {
        db.readWrite { implicit session =>
          val userHandle = Username("myuserhandle")
          val orgHandle = OrganizationHandle("myorghandle")
          val userTemplate = UserFactory.user().withUsername(userHandle.value).saved
          val user = handleCommander.setUsername(userTemplate, userHandle).get
          val org = OrganizationFactory.organization().withHandle(orgHandle).withOwner(user).saved
          (user, org)
        }
      }
      "get a user" in {
        withDb(modules: _*) { implicit injector =>
          val (user, _) = testSetup

          inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ORGANIZATION))
          val request = route.getByHandle(user.username)
          val response = controller.getByHandle(user.username, authTokenOpt = None)(request)

          val jsonResponse = Json.parse(contentAsString(response))
          (jsonResponse \ "type").as[String] === "user"
        }
      }
      "get an org" in {
        withDb(modules: _*) { implicit injector =>
          val (user, org) = testSetup

          inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ORGANIZATION))
          val request = route.getByHandle(org.handle)
          val response = controller.getByHandle(org.handle, authTokenOpt = None)(request)

          val jsonResponse = Json.parse(contentAsString(response))
          (jsonResponse \ "type").as[String] === "org"
        }
      }
    }
  }

  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]

  private def route = com.keepit.controllers.website.routes.UserOrOrganizationController

  private def controller(implicit injector: Injector) = inject[UserOrOrganizationController]
}

