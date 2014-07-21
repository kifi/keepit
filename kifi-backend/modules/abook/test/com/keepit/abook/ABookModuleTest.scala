package com.keepit.abook

import com.keepit.test.{ DeprecatedTestApplication, DeprecatedTestRemoteGlobal }
import java.io.File
import org.specs2.mutable.Specification
import com.keepit.common.logging.Logging
import com.keepit.inject.ApplicationInjector
import play.api.mvc.Controller
import com.keepit.common.controller.ServiceController
import com.keepit.common.net.FakeClientResponse
import com.keepit.common.akka.{ AlertingActor, FortyTwoActor }
import net.spy.memcached.MemcachedClient
import com.keepit.common.zookeeper.ServiceCluster
import play.api.test.Helpers.running
import play.api.Play.current
import scala.reflect.ManifestFactory.classType
import scala.collection.JavaConversions._
import com.keepit.abook.controllers.ABookController

class DeprecatedABookApplication(global: DeprecatedTestRemoteGlobal)
    extends DeprecatedTestApplication(global, useDb = false, path = new File("./modules/abook/")) {
}

class ABookModuleTest extends Specification with Logging with ApplicationInjector {

  private def isABookController(clazz: Class[_]): Boolean = {
    if (classOf[Controller] isAssignableFrom clazz) {
      if (classOf[ServiceController] isAssignableFrom clazz) {
        classOf[ABookController] isAssignableFrom clazz
      } else throw new IllegalStateException(s"class $clazz is a controller that does not extends a service controller")
    } else false
  }

  val global = new DeprecatedTestRemoteGlobal(ABookTestModule())

  "Module" should {
    "instantiate controllers" in {
      running(new DeprecatedABookApplication(global).withFakeHttpClient(FakeClientResponse.fakeAmazonDiscoveryClient)) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isABookController)
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
