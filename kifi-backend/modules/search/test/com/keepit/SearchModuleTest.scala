package com.keepit.search

import com.keepit.common.zookeeper._
import com.keepit.common.net._
import com.keepit.common.controller.{ServiceController, SearchServiceController}
import com.keepit.common.logging.Logging
import com.keepit.test.{SearchApplicationInjector, DeprecatedSearchApplication}
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers.running
import scala.collection.JavaConversions._
import com.keepit.common.akka.{FortyTwoActor,AlertingActor}
import net.spy.memcached.MemcachedClient
import scala.reflect.ManifestFactory.classType

class SearchModuleTest extends Specification with Logging with SearchApplicationInjector {

  private def isSearchController(clazz: Class[_]): Boolean = {
    if (classOf[Controller] isAssignableFrom clazz) {
      if (classOf[ServiceController] isAssignableFrom clazz) {
        classOf[SearchServiceController] isAssignableFrom clazz
      } else throw new IllegalStateException(s"class $clazz is a controller that does not extends a service controller")
    } else false
  }

  "Module" should {
    "instantiate controllers" in {
      running(new DeprecatedSearchApplication().withFakeHttpClient(FakeClientResponse.fakeAmazonDiscoveryClient)) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isSearchController)
        for (c <- classes) inject(classType[Controller](c), injector)
        val bindings = injector.getAllBindings()
        val exclude: Set[Class[_]] = Set(classOf[FortyTwoActor], classOf[AlertingActor], classOf[akka.actor.Actor], classOf[MemcachedClient])
        bindings.keySet() filter { key =>
          val klazz = key.getTypeLiteral.getRawType
          val fail = exclude exists { badKalazz =>
            badKalazz.isAssignableFrom(klazz)
          }
          !fail
        } foreach { key =>
          injector.getInstance(key)
        }
        injector.getInstance(classOf[ServiceCluster])
        true
      }
    }
  }
}
