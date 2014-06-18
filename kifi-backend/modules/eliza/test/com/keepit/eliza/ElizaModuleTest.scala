package com.keepit.eliza

import com.keepit.common.zookeeper._
import com.keepit.common.net._
import com.keepit.common.controller._
import com.keepit.common.logging.Logging
import com.keepit.test.DeprecatedElizaApplication
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers.running
import scala.collection.JavaConversions._
import com.keepit.common.akka.{FortyTwoActor,AlertingActor}
import net.spy.memcached.MemcachedClient
import com.keepit.inject.ApplicationInjector
import scala.reflect.ManifestFactory.classType
import com.keepit.heimdal.HeimdalQueueDevModule

class ElizaModuleTest extends Specification with Logging with ApplicationInjector {

  private def isElizaController(clazz: Class[_]): Boolean = {
    if (classOf[Controller] isAssignableFrom clazz) {
      if (classOf[ServiceController] isAssignableFrom clazz) {
        classOf[ElizaServiceController] isAssignableFrom clazz
      } else throw new IllegalStateException(s"class $clazz is a controller that does not extends a service controller")
    } else false
  }

  "Module" should {
    "instantiate controllers" in {
      running(new DeprecatedElizaApplication().withFakeHttpClient(FakeClientResponse.fakeAmazonDiscoveryClient).overrideWith(HeimdalQueueDevModule())) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isElizaController)
        for (c <- classes) inject(classType[Controller](c), injector)
        val bindings = injector.getAllBindings
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
