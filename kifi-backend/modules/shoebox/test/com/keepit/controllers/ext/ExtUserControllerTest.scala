package com.keepit.controllers.ext

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest

import scala.concurrent.Future

class ExtUserControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {
  implicit val context = HeimdalContext.empty
  val controllerTestModules = Seq(
    FakeCryptoModule(),
    FakeShoeboxServiceModule(),
    FakeKeepImportsModule(),
    FakeSliderHistoryTrackerModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeHttpClientModule()
  )

  "ExtUserController" should {
    "search for contacts" in {
      skipped("no tests :(")
    }
  }
  private def controller(implicit injector: Injector) = inject[ExtUserController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}
