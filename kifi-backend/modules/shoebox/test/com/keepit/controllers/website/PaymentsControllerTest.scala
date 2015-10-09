package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.payments.{ PlanManagementCommander, PaidPlan, FakeStripeClientModule }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.JsObject
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._

class PaymentsControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[PaymentsController]
  private def route = com.keepit.controllers.website.routes.PaymentsController

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeStripeClientModule()
  )

  "PaymentsController" should {

    def setup()(implicit injector: Injector) = {
      db.readWrite { implicit session =>
        val owner = UserFactory.user().saved
        val org = OrganizationFactory.organization().withOwner(owner).saved
        (org, owner)
      }
    }

    "get an account's state" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val planCommander = inject[PlanManagementCommander]
        val (org, owner) = setup()

        val publicId = Organization.publicId(org.id.get)
        inject[FakeUserActionsHelper].setUser(owner)
        val request = route.getAccountState(publicId)
        val response = controller.getAccountState(publicId)(request)
        val payload = contentAsJson(response).as[JsObject]

        (payload \ "users").as[Int] must beEqualTo(1)
        (payload \ "credit").as[Int] must beEqualTo(-4677)

        val planJson = (payload \ "plan").as[JsObject]
        val actualPlan = planCommander.currentPlan(org.id.get)
        (planJson \ "id").as[PublicId[PaidPlan]] must beEqualTo(PaidPlan.publicId(actualPlan.id.get))
        (planJson \ "name").as[String] must beEqualTo("test")
        (planJson \ "pricePerUser").as[Int] must beEqualTo(10000)
        (planJson \ "cycle").as[Int] must beEqualTo(1)
        1 === 1
      }
    }

    "get an organization's configuration" in {
      "use the correct format" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner) = setup()
          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(owner)
          val request = route.getAccountFeatureSettings(publicId)
          val response = controller.getAccountFeatureSettings(publicId)(request)
          val payload = contentAsJson(response).as[JsObject]

          (payload \ "name").as[String] === "test"
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "setting").as[String] === "members"
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "editable").as[Boolean] === true
        }
      }
    }
  }
}
