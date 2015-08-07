package com.keepit.controllers.admin

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.LibraryMembershipCommander
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsNull, JsValue, Json }
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._

class AdminLibraryControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  private def controller(implicit injector: Injector) = inject[AdminLibraryController]
  private def route = com.keepit.controllers.admin.routes.AdminLibraryController
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "AdminLibraryController" should {
    "inject" in {
      withDb(modules: _*) { implicit injector =>
        inject[AdminLibraryController] !== null
      }
    }
    "let admins put people in libraries" in {
      "never allow a non-admin to do anything" in {
        withDb(modules: _*) { implicit injector =>
          val rando = db.readWrite { implicit session => UserFactory.user().saved }

          inject[FakeUserActionsHelper].setUser(rando)
          val payload: JsValue = JsNull
          val request = route.unsafeAddMember().withBody(payload)
          val result = controller.unsafeAddMember(request)
          status(result) === FORBIDDEN
        }
      }
      "force a user into a library" in {
        withDb(modules: _*) { implicit injector =>
          val (admin, lib, rando) = db.readWrite { implicit session =>
            val admin = UserFactory.user().saved
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(owner).saved
            (admin, lib, rando)
          }

          inject[FakeUserActionsHelper].setUser(admin, Set(UserExperimentType.ADMIN))
          val payload: JsValue = Json.obj("userId" -> rando.id.get, "libraryId" -> lib.id.get, "access" -> LibraryAccess.READ_WRITE)
          val request = route.unsafeAddMember().withBody(payload)
          val result = controller.unsafeAddMember(request)
          status(result) === OK

          val membership = contentAsJson(result).as[LibraryMembership]
          membership.userId === rando.id.get
          membership.libraryId === lib.id.get
        }
      }
    }
  }
}
