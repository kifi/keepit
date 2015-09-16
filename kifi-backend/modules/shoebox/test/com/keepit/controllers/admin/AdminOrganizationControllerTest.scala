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
import play.api.libs.json.{ JsString, JsArray, JsValue, Json }
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
    "give all organizations a new permission" in {
      withDb(modules: _*) { implicit injector =>
        val (admin, orgs) = db.readWrite { implicit session =>
          val admin = UserFactory.user().saved
          val orgs = OrganizationFactory.organizations(8).map(_.withOwner(UserFactory.user().saved)).saved
          (admin, orgs)
        }

        val targetPermission = FORCE_EDIT_LIBRARIES

        db.readOnlyMaster { implicit session =>
          orgRepo.all.forall { org => org.basePermissions.forRole(OrganizationRole.ADMIN) === Organization.defaultBasePermissions.forRole(OrganizationRole.ADMIN) }
          orgRepo.all.forall { org => org.basePermissions.forRole(OrganizationRole.MEMBER).contains(targetPermission) === false }
        }

        inject[FakeUserActionsHelper].setUser(admin, Set(UserExperimentType.ADMIN))
        val payload: JsValue = Json.obj("permissions" -> Json.obj("add" -> Json.obj(OrganizationRole.ADMIN.value -> Seq(targetPermission.value))), "confirmation" -> "i swear i know what i am doing")
        val request = route.addPermissionToAllOrganizations().withBody(payload)
        val result = controller.addPermissionToAllOrganizations()(request)
        status(result) === OK

        inject[WatchableExecutionContext].drain()

        db.readOnlyMaster { implicit session =>
          orgRepo.all.forall { org => org.basePermissions.forRole(OrganizationRole.ADMIN) === Organization.defaultBasePermissions.forRole(OrganizationRole.ADMIN) + targetPermission }
          orgRepo.all.forall { org => org.basePermissions.forRole(OrganizationRole.MEMBER).contains(targetPermission) === false }
        }
        1 === 1
      }
    }
  }
}
