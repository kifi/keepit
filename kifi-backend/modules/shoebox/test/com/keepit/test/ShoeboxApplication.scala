package com.keepit.test

import com.keepit.common.controller._
import com.keepit.common.crypto.FakeCryptoModule
import play.api.Mode
import com.keepit.inject.{ TestFortyTwoModule, ApplicationInjector, EmptyInjector }
import com.keepit.common.db.TestDbInfo
import java.io.File
import com.keepit.common.time.FakeClockModule
import com.keepit.common.db.TestSlickModule
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.scraper.TestScraperServiceClientModule
import com.keepit.normalizer.TestNormalizationServiceModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.shoebox._
import com.keepit.common.actor.{ TestActorSystemModule, TestSchedulerModule }
import com.keepit.common.queue.{ FakeSimpleQueueModule }
import com.keepit.queue.FakeNormalizationUpdateJobQueueModule
import com.keepit.common.aws.AwsModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.inject.TestFortyTwoModule
import com.keepit.scraper.TestScraperServiceClientModule
import com.keepit.shoebox.AbuseControlModule
import com.keepit.shoebox.FakeShoeboxRepoChangeListenerModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.queue.FakeSimpleQueueModule
import com.keepit.common.time.FakeClockModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.common.actor.TestSchedulerModule
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.healthcheck.FakeMemoryUsageModule
import com.keepit.common.aws.AwsModule
import com.keepit.queue.FakeNormalizationUpdateJobQueueModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.normalizer.TestNormalizationServiceModule
import com.keepit.common.db.TestSlickModule

class ShoeboxApplication(overridingModules: Module*)(implicit path: File = new File("./modules/shoebox/"))
  extends DbTestApplication(path, overridingModules, Seq(
    ShoeboxServiceTypeModule(),
    TestABookServiceClientModule(),
    TestHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestFortyTwoModule(),
    FakeDiscoveryModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    TestNormalizationServiceModule(),
    FakeActionAuthenticatorModule(),
    AbuseControlModule(),
    TestSchedulerModule(),
    FakeKeepImportsModule(),
    FakeSimpleQueueModule(),
    FakeNormalizationUpdateJobQueueModule(),
    AwsModule(),
    FakeShoeboxRepoChangeListenerModule(),
    FakeCryptoModule()
  ))

trait ShoeboxApplicationInjector extends ApplicationInjector with DbInjectionHelper with ShoeboxInjectionHelpers

trait ShoeboxTestInjector extends TestInjector with DbInjectionHelper with ShoeboxInjectionHelpers {
  val module = Modules.combine(
    ShoeboxServiceTypeModule(),
    TestHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    TestNormalizationServiceModule(),
    TestScraperServiceClientModule(),
    AbuseControlModule(),
    TestSchedulerModule(),
    FakeSimpleQueueModule(),
    FakeNormalizationUpdateJobQueueModule(),
    AwsModule(),
    FakeShoeboxRepoChangeListenerModule()
  )
}
