package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsString, Json }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class MobileOrganizationControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "MobileOrganizationController" should {
    def setup(numMembers: Int = 0, numInvitedUsers: Int = 0)(implicit injector: Injector) = {
      db.readWrite { implicit session =>
        val members = UserFactory.users(numMembers).saved
        val invitedUsers = UserFactory.users(numInvitedUsers).saved
        val owner = UserFactory.user().withName("A", "Moneybags").saved

        val org = OrganizationFactory.organization()
          .withName("Moneybags, LLC")
          .withOwner(owner)
          .withMembers(members)
          .withInvitedUsers(invitedUsers)
          .saved

        (org, owner, members, invitedUsers)
      }
    }
    "create an organization" in {
      "reject malformed input" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session => UserFactory.user().withName("foo", "bar").saved }

          inject[FakeUserActionsHelper].setUser(user)
          val request = route.createOrganization().withBody(JsString("{i am really horrible at json}"))
          val result = controller.createOrganization(request)
          status(result) === BAD_REQUEST
        }
      }
      "let a user create an organization" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val user = db.readWrite { implicit session => UserFactory.user().withName("foo", "bar").saved }

          val createRequest = OrganizationCreateRequest(userId = user.id.get, orgName = "Banana Capital, USA")
          val createRequestJson = Json.toJson(createRequest)

          inject[FakeUserActionsHelper].setUser(user)
          val request = route.createOrganization().withBody(createRequestJson)
          val result = controller.createOrganization(request)
          status(result) === OK

          val createResponseJson = Json.parse(contentAsString(result))
          val createResponse = createResponseJson.as[OrganizationCreateResponse]
          createResponse.request === createRequest
        }
      }
    }
  }

  private def controller(implicit injector: Injector) = inject[MobileOrganizationController]
  private def route = com.keepit.controllers.mobile.routes.MobileOrganizationController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      contentType(result) must beSome("application/json")
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
    }
  }
}
