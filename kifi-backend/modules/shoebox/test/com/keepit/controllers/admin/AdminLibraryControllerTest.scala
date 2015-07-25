package com.keepit.controllers.admin

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class AdminLibraryControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule())

  args(skipAll = true)

  "AdminLibraryController" should {
    "inject" in {
      withDb(modules: _*) { implicit injector =>
        inject[AdminLibraryController] !== null
      }
    }
  }
}
