package com.keepit.scraper

import com.keepit.common.store.ScraperTestStoreModule
import com.keepit.common.cache._
import com.keepit.inject.CommonDevModule
import com.keepit.common.zookeeper._
import com.keepit.common.net._
import com.keepit.common.logging.Logging
import play.api.test.Helpers.running
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import scala.collection.JavaConversions._
import com.keepit.common.akka.{ FortyTwoActor, AlertingActor }
import net.spy.memcached.MemcachedClient
import com.keepit.inject.ApplicationInjector
import scala.reflect.ManifestFactory.classType
import com.keepit.test.{ DeprecatedTestRemoteGlobal, DeprecatedTestApplication }
import java.io.File
import com.keepit.common.controller.{ ScraperServiceController, ShoeboxServiceController, ServiceController }
import com.keepit.common.concurrent.DevForkJoinContextMonitorModule
import com.keepit.scraper.embedly.DevEmbedlyModule

class DeprecatedScraperApplication(global: DeprecatedTestRemoteGlobal)
    extends DeprecatedTestApplication(global, useDb = false, path = new File("./modules/scraper/")) {
}

class ScraperModuleTest extends Specification with Logging with ApplicationInjector {

  private def isScraperController(clazz: Class[_]): Boolean = {
    if (classOf[Controller] isAssignableFrom clazz) {
      if (classOf[ServiceController] isAssignableFrom clazz) {
        classOf[ScraperServiceController] isAssignableFrom clazz
      } else throw new IllegalStateException(s"class $clazz is a controller that does not extends a service controller")
    } else false
  }

  val global = new DeprecatedTestRemoteGlobal(TestScraperServiceModule())

  "Module" should {
    "instantiate controllers" in {
      running(new DeprecatedScraperApplication(global).
        withFakeHttpClient(FakeClientResponse.fakeAmazonDiscoveryClient)) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isScraperController)
        for (c <- classes) inject(classType[Controller](c), injector)
        val bindings = injector.getAllBindings()
        val exclude: Set[Class[_]] = Set(classOf[FortyTwoActor], classOf[AlertingActor], classOf[akka.actor.Actor], classOf[MemcachedClient])
        bindings.keySet() filter { key =>
          val klazz = key.getTypeLiteral.getRawType
          val fail = exclude exists { badKlazz =>
            badKlazz.isAssignableFrom(klazz)
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
