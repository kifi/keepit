package com.keepit.shoebox

import com.keepit.search._
import com.keepit.common.zookeeper._
import com.keepit.FortyTwoGlobal
import com.keepit.common.akka.{FortyTwoActor,AlertingActor}
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import com.keepit.common.mail.MailToKeepServerSettings
import com.keepit.inject.inject
import com.keepit.test.ShoeboxApplication
import net.spy.memcached.MemcachedClient
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers.running
import scala.collection.JavaConversions._
import scala.reflect.ManifestFactory.classType
import com.keepit.common.net.FakeClientResponse
import com.keepit.common.zookeeper.ServiceDiscovery

class ShoeboxModuleTest extends Specification with Logging {

  private def isShoeboxController(clazz: Class[_]): Boolean = {
    classOf[ShoeboxServiceController] isAssignableFrom clazz
  }

  "Module" should {
    "instantiate controllers" in {
      running(new ShoeboxApplication().withFakeMail().withFakeCache().withFakeHttpClient(FakeClientResponse.fakeAmazonDiscoveryClient)
          .withS3DevModule().withFakePersistEvent.withShoeboxServiceModule.withSearchConfigModule) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isShoeboxController)
        for (c <- classes) inject(classType[Controller](c), current)
        val injector = current.global.asInstanceOf[FortyTwoGlobal].injector
        val bindings = injector.getAllBindings
        val exclude: Set[Class[_]] = Set(classOf[FortyTwoActor], classOf[AlertingActor],
          classOf[MailToKeepServerSettings], classOf[MemcachedClient])
        bindings.keySet() filter { key =>
          val klazz = key.getTypeLiteral.getRawType
          val fail = exclude exists { badKalazz =>
            badKalazz.isAssignableFrom(klazz)
          }
          !fail
        } foreach { key =>
          injector.getInstance(key)
        }
        injector.getInstance(classOf[SearchServiceClient])
        injector.getInstance(classOf[ServiceDiscovery])
        injector.getInstance(classOf[ServiceCluster])
        true
      }
    }
  }
}
