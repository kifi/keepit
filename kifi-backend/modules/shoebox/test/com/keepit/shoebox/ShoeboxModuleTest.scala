package com.keepit.shoebox

import us.theatr.akka.quartz.QuartzActor
import com.keepit.search._
import com.keepit.reports._
import com.keepit.common.zookeeper._
import com.keepit.common.akka.{FortyTwoActor,AlertingActor}
import com.keepit.common.controller.{ServiceController, ShoeboxServiceController}
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
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.social.{FakeSocialGraphModule, FakeShoeboxSecureSocialModule}
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.model.TestSliderHistoryTrackerModule
import com.keepit.classify.FakeDomainTagImporterModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.scraper.{TestScrapeSchedulerConfigModule, TestScraperServiceClientModule, FakeScrapeSchedulerModule}
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.graph.TestGraphServiceClientModule
import com.keepit.signal.TestReKeepStatsUpdaterModule
import com.keepit.normalizer.{NormalizationServiceImpl, NormalizedURIInterner}
import controllers.AssetsBuilder

class ShoeboxModuleTest extends Specification with Logging with ShoeboxApplicationInjector {

  private def isShoeboxController(clazz: Class[_]): Boolean = {
    if ((classOf[Controller] isAssignableFrom clazz) && !(classOf[AssetsBuilder] isAssignableFrom clazz)) {
      if (classOf[ServiceController] isAssignableFrom clazz) {
        classOf[ShoeboxServiceController] isAssignableFrom clazz
      } else throw new IllegalStateException(s"class $clazz is a controller that does not extends a service controller")
    } else false
  }

  "Module" should {
    "instantiate controllers" in {
      running(new ShoeboxApplication(
        ShoeboxSlickModule(),
        FakeMailModule(),
        FakeHttpClientModule(FakeClientResponse.fakeAmazonDiscoveryClient),
        FakeDiscoveryModule(),
        TestActorSystemModule(),
        FakeShoeboxSecureSocialModule(),
        ShoeboxFakeStoreModule(),
        FakeSocialGraphModule(),
        TestAnalyticsModule(),
        TestSliderHistoryTrackerModule(),
        TestSearchServiceClientModule(),
        FakeDomainTagImporterModule(),
        FakeCortexServiceClientModule(),
        TestGraphServiceClientModule(),
        GeckoboardModule(),
        FakeShoeboxServiceModule(), // This one should not be required once the Scraper is off Shoebox
        FakeScrapeSchedulerModule(), // This one should not be required once the Scraper is off Shoebox
        TestScrapeSchedulerConfigModule(),
        FakeElizaServiceClientModule(),
        FakeAirbrakeModule(),
        TestHeimdalServiceClientModule(),
        TestABookServiceClientModule(),
        TestScraperServiceClientModule(),
        KeepImportsModule(),
        FakeExternalServiceModule(),
        TestReKeepStatsUpdaterModule()
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
        injector.getInstance(classOf[NormalizedURIInterner])
        injector.getInstance(classOf[NormalizationServiceImpl])
        true
      }
    }
  }
}
