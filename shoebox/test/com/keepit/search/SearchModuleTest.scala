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
import com.keepit.common.analytics.EventListenerPlugin
import com.keepit.common.analytics.EventRepo
import com.keepit.FortyTwoGlobal
import scala.collection.JavaConversions._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.analytics.EventHelper
import net.spy.memcached.MemcachedClient

class SearchModuleTest extends Specification with Logging {

  private def isSearchController(clazz: Class[_]): Boolean = {
    classOf[SearchServiceController] isAssignableFrom clazz
  }

  "Module" should {
    "instantiate controllers" in {
      running(new SearchApplication().withFakeHealthcheck().withFakeMail().withFakeCache()) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isSearchController)
        for (c <- classes) inject(classType[Controller](c), current)
        val injector = current.global.asInstanceOf[FortyTwoGlobal].injector
        val bindings = injector.getAllBindings()
        val exclude: Set[Class[_]] = Set(
          classOf[FortyTwoActor], classOf[MemcachedClient])
        bindings.keySet() filter { key =>
          val klazz = key.getTypeLiteral.getRawType
          !exclude.contains(klazz) && !exclude.contains(klazz.getSuperclass)
        } foreach { key =>
          injector.getInstance(key)
        }
        inject[EventRepo]
        inject[EventHelper]
        true
      }
    }
  }
}
