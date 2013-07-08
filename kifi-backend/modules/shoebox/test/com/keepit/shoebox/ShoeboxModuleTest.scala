package com.keepit.shoebox

import com.keepit.search._
import com.keepit.common.zookeeper._
import com.keepit.common.akka.{FortyTwoActor,AlertingActor}
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{FakeMailModule, MailToKeepServerSettings}
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector, DeprecatedShoeboxApplication}
import net.spy.memcached.MemcachedClient
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers.running
import scala.collection.JavaConversions._
import scala.reflect.ManifestFactory.classType
import com.keepit.common.net.{FakeHttpClientModule, FakeClientResponse}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.social.{FakeSocialGraphModule, TestShoeboxSecureSocialModule}
import com.keepit.common.store.FakeStoreModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.model.TestSliderHistoryTrackerModule
import com.keepit.scraper.FakeScraperModule
import net.codingwell.scalaguice.ScalaModule
import com.keepit.classify.FakeDomainTagImporterModule
import com.keepit.learning.topicmodel.FakeWordTopicModule
import com.keepit.learning.topicmodel.DevTopicModelModule

class ShoeboxModuleTest extends Specification with Logging with ShoeboxApplicationInjector {

  // This should not be required once the Scraper is off Shoebox
  case class FakeScraperInShoeboxModule() extends ScalaModule {
    def configure() {
      install(FakeScraperModule())
      install(FakeShoeboxServiceModule())
    }
  }

  private def isShoeboxController(clazz: Class[_]): Boolean = {
    classOf[ShoeboxServiceController] isAssignableFrom clazz
  }

  "Module" should {
    "instantiate controllers" in {
      running(new ShoeboxApplication(
        FakeMailModule(),
        FakeHttpClientModule(FakeClientResponse.fakeAmazonDiscoveryClient),
        FakeDiscoveryModule(),
        ShoeboxWebSocketModule(),
        TestActorSystemModule(),
        TestShoeboxSecureSocialModule(),
        FakeStoreModule(),
        FakeSocialGraphModule(),
        TestAnalyticsModule(),
        TestSliderHistoryTrackerModule(),
        TestSearchServiceClientModule(),
        FakeScraperInShoeboxModule(),
        FakeDomainTagImporterModule(),
        FakeWordTopicModule(),
        DevTopicModelModule()
      )) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isShoeboxController)
        for (c <- classes) inject(classType[Controller](c), injector)
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
