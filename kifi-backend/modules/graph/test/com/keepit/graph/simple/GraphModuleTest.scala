package com.keepit.graph.simple

import com.keepit.common.zookeeper._
import com.keepit.common.net._
import com.keepit.common.controller._
import com.keepit.common.logging.Logging
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers.running
import scala.collection.JavaConversions._
import com.keepit.common.akka.{ FortyTwoActor, AlertingActor }
import net.spy.memcached.MemcachedClient
import com.keepit.inject.ApplicationInjector
import scala.reflect.ManifestFactory.classType
import com.keepit.graph.test.GraphApplication
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.graph.common.store.GraphFakeStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule

class GraphModuleTest extends Specification with Logging with ApplicationInjector {

  private def isGraphController(clazz: Class[_]): Boolean = {
    if (classOf[Controller] isAssignableFrom clazz) {
      if (classOf[ServiceController] isAssignableFrom clazz) {
        classOf[GraphServiceController] isAssignableFrom clazz
      } else throw new IllegalStateException(s"class $clazz is a controller that does not extends a service controller")
    } else false
  }

  "Module" should {
    "instantiate controllers" in {
      running(new GraphApplication(
        SimpleGraphDevModule(),
        GraphFakeStoreModule(),
        TestActorSystemModule(),
        FakeCortexServiceClientModule(),
        FakeHttpClientModule(FakeClientResponse.fakeAmazonDiscoveryClient)
      )) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).toSeq.flatten.collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isGraphController)
        for (c <- classes) {
          println(s"Instantiating controller: $c")
          inject(classType[Controller](c), injector)
        }
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