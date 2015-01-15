package com.keepit.controllers.admin

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model._

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import play.api.test._

class AdminLibraryControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule())

  "AdminLibraryController" should {
    "inject" in {
      withDb(modules: _*) { implicit injector =>
        inject[AdminLibraryController] !== null
      }
    }
  }
}
