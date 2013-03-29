package com.keepit.search

import com.keepit.common.controller.SearchServiceController
import com.keepit.common.logging.Logging
import com.keepit.inject.inject
import com.keepit.test.SearchApplication
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers.running
import scala.reflect.ManifestFactory.classType

class SearchModuleTest extends Specification with Logging {

  private def isSearchController(clazz: Class[_]): Boolean = {
    classOf[SearchServiceController] isAssignableFrom clazz
  }

  "Module" should {
    "instantiate controllers" in {
      running(new SearchApplication().withFakeHealthcheck().withFakeMail()) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isSearchController)
        for (c <- classes) inject(classType[Controller](c), current)
        true
      }
    }
  }
}
