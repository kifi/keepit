package com.keepit.dev

import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers._
import scala.reflect.Manifest.classType

class DevModuleTest extends Specification with Logging {

  "Module" should {
    "instantiate controllers" in {
      running(new DevApplication().withFakeMail()) {
        val ClassRoute = "@(.+)@.+".r
        log.info("xxxxxx\n\n" + current.configuration.getString("application.router").map(_ + "$").getOrElse("Routes$"))
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct
        for (c <- classes) inject(classType[Controller](c), current)
        true
      }
    }
  }
}
