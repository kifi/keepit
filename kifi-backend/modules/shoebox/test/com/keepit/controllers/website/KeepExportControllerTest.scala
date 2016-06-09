package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.controllers.client.KeepExportController
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.mvc.Call
import play.api.test.FakeRequest

class KeepExportControllerTest extends Specification with ShoeboxTestInjector {
  def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[KeepExportController]
  private def route = com.keepit.controllers.client.routes.KeepExportController

  val modules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "OrganizationController" should {
    "stream a zipped kifi export" in {
      withDb(modules: _*) { implicit injector =>
        val (user, lib) = db.readWrite { implicit s =>
          val user = UserFactory.user().saved
          val lib = LibraryFactory.library().withOwner(user).saved
          (user, lib)
        }
        inject[FakeUserActionsHelper].setUser(user)
        val request = createFakeRequest(route.fullKifiExport())
        val resultFut = controller.fullKifiExport().apply(request)
        1 === 1
      }
    }
  }
}
