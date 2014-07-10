package com.keepit.dev

import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers._
import scala.reflect.Manifest.classType
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.healthcheck.{ DevAirbrakeModule, FakeMemoryUsageModule }

class DevModuleTest extends Specification with Logging with ApplicationInjector {

  "Module" should {
    "instantiate controllers" in {
      running(new DevApplication(FakeMailModule(), DevAirbrakeModule(), FakeMemoryUsageModule(), ShoeboxFakeStoreModule())) {
        val ClassRoute = "@(.+)@.+".r
        log.info("xxxxxx\n\n" + current.configuration.getString("application.router").map(_ + "$").getOrElse("Routes$"))
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct
        for (c <- classes) inject(classType[Controller](c), injector)
        true
      }
    }
  }
}
