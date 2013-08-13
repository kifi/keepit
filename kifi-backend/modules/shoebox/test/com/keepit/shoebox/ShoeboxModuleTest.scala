package com.keepit.shoebox

import us.theatr.akka.quartz.QuartzActor
import com.keepit.search._
import com.keepit.reports._
import com.keepit.common.zookeeper._
import com.keepit.common.akka.{FortyTwoActor,AlertingActor}
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{FakeMailModule, MailToKeepServerSettings}
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector}
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
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.model.TestSliderHistoryTrackerModule
import com.keepit.classify.FakeDomainTagImporterModule
import com.keepit.learning.topicmodel.FakeWordTopicModule
import com.keepit.learning.topicmodel.DevTopicModelModule
import com.keepit.learning.topicmodel.DevTopicStoreModule
import com.keepit.eliza.TestElizaServiceClientModule
import com.keepit.scraper.FakeScraperModule

class ShoeboxModuleTest extends Specification with Logging with ShoeboxApplicationInjector {

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
        ShoeboxFakeStoreModule(),
        FakeSocialGraphModule(),
        TestAnalyticsModule(),
        TestSliderHistoryTrackerModule(),
        TestSearchServiceClientModule(),
        FakeDomainTagImporterModule(),
        FakeWordTopicModule(),
        DevTopicModelModule(),
        DevTopicStoreModule(),
        GeckoboardModule(),
        FakeShoeboxServiceModule(), // This one should not be required once the Scraper is off Shoebox
        FakeScraperModule(), // This one should not be required once the Scraper is off Shoebox
        TestElizaServiceClientModule()
      )) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isShoeboxController)
        for (c <- classes) inject(classType[Controller](c), injector)
        val bindings = injector.getAllBindings
        val exclude: Set[Class[_]] = Set(classOf[FortyTwoActor], classOf[AlertingActor], classOf[QuartzActor],
          classOf[MailToKeepServerSettings], classOf[MemcachedClient])
        bindings.keySet() filter { key =>
          val klazz = key.getTypeLiteral.getRawType
          val fail = exclude exists { badKalazz =>
            badKalazz.isAssignableFrom(klazz)
          }
          !fail
        } foreach { key =>
          try {
            injector.getInstance(key)
          } catch {
            case e: Throwable =>
              throw new Exception(s"can't instantiate $key", e)
          }
        }
        injector.getInstance(classOf[SearchServiceClient])
        injector.getInstance(classOf[ServiceDiscovery])
        injector.getInstance(classOf[ServiceCluster])
        injector.getInstance(classOf[GeckoboardReporterPlugin])
        true
      }
    }
  }
}
