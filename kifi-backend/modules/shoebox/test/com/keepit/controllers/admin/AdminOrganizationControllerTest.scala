package com.keepit.controllers.admin

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.WatchableExecutionContext
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.OrganizationPermission._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsValue, Json }
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._

class AdminOrganizationControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  private def controller(implicit injector: Injector) = inject[AdminOrganizationController]
  private def route = com.keepit.controllers.admin.routes.AdminOrganizationController
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "AdminOrganizationController" should {
  }
}
