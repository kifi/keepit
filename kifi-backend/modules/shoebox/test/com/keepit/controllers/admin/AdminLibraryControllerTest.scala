package com.keepit.controllers.admin

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.LibraryMembershipCommander
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
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

  args(skipAll = true)

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
    "let admins move keeps in and out of libraries" in {
      "never allow a non-admin to do anything" in {
        withDb(modules: _*) { implicit injector =>
          val rando = db.readWrite { implicit session => UserFactory.user().saved }

          inject[FakeUserActionsHelper].setUser(rando)
          val payload: JsValue = JsNull
          val request = route.unsafeMoveLibraryKeeps.withBody(payload)
          val result = controller.unsafeMoveLibraryKeeps(request)
          status(result) === FORBIDDEN
        }
      }
      "migrate all the keeps from one lib into another" in {
        withDb(modules: _*) { implicit injector =>
          val (admin, lib1, lib2, owner) = db.readWrite { implicit session =>
            val admin = UserFactory.user().saved
            val owner = UserFactory.user().saved
            val lib1 = LibraryFactory.library().withOwner(owner).saved
            val lib2 = LibraryFactory.library().withOwner(owner).saved
            KeepFactory.keeps(20).map(_.withLibrary(lib1).withUser(owner).saved)

            ktlRepo.getCountByLibraryId(lib1.id.get) === 20
            ktlRepo.getCountByLibraryId(lib2.id.get) === 0

            (admin, lib1, lib2, owner)
          }

          inject[FakeUserActionsHelper].setUser(admin, Set(UserExperimentType.ADMIN))
          val payload: JsValue = Json.obj("fromLibrary" -> lib1.id.get, "toLibrary" -> lib2.id.get)
          val request = route.unsafeMoveLibraryKeeps().withBody(payload)
          val result = controller.unsafeMoveLibraryKeeps(request)
          status(result) === OK

          val resultJson = contentAsJson(result)
          val moved = (resultJson \ "moved").as[Seq[Keep]]
          val failed = (resultJson \ "failures").as[Seq[JsValue]]
          moved.length === 20
          failed.length === 0

          db.readOnlyMaster { implicit session =>
            ktlRepo.getCountByLibraryId(lib1.id.get) === 0
            ktlRepo.getCountByLibraryId(lib2.id.get) === 20
          }
        }
      }
    }
  }
}
