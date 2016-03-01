package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.payments._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._

class OrganizationConfigControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[OrganizationConfigController]
  private def route = com.keepit.controllers.website.routes.OrganizationConfigController

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "OrganizationConfigController" should {

    def setup()(implicit injector: Injector) = {
      db.readWrite { implicit session =>
        val owner = UserFactory.user().saved
        val member = UserFactory.user().saved
        val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
        val account = inject[PaidAccountRepo].getByOrgId(org.id.get)
        val plan = inject[PaidPlanRepo].get(account.planId)
        (org, owner, account, plan)
      }
    }

    "get an organization's configuration" in {
      "use the correct format" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, _, _) = setup()
          val publicId = Organization.publicId(org.id.get)
          inject[FakeUserActionsHelper].setUser(owner)
          val request = route.getAccountFeatureSettings(publicId)
          val response = controller.getAccountFeatureSettings(publicId)(request)
          val payload = contentAsJson(response).as[JsObject]
          (payload \ "showUpsells").as[Boolean] === true
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "setting").as[String] === "members"
          ((payload \ "settings").as[JsObject] \ "publish_libraries" \ "editable").as[Boolean] === true
        }
      }
    }

  }
}
