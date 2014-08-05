package com.keepit.controllers.website

import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule }
import com.keepit.test.{ ShoeboxTestInjector }
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext.Implicits.global

class RecommendationsControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeCuratorServiceClientModule()
  )

  "RecommendationsController" should {

    "call adHocRecos" in {
      withInjector(modules: _*) { implicit injector =>

        val route = com.keepit.controllers.website.routes.RecommendationsController.adHocRecos(1).url

        route === "/site/recos/adHoc?n=1"
      }
    }
  }
}
