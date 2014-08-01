package com.keepit.test

import java.io.File

import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.actor.{ TestActorSystemModule, TestSchedulerModule }
import com.keepit.common.aws.AwsModule
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.{ TestDbInfo, TestSlickModule }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.keepit.common.queue.FakeSimpleQueueModule
import com.keepit.common.social.TestShoeboxAppSecureSocialModule
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.inject.{ ApplicationInjector, TestFortyTwoModule }
import com.keepit.normalizer.TestNormalizationServiceModule
import com.keepit.queue.FakeNormalizationUpdateJobQueueModule
import com.keepit.scraper.TestScraperServiceClientModule
import com.keepit.shoebox._

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
    FakeShoeboxRepoChangeListenerModule(),
    FakeCryptoModule(),
    TestActorSystemModule(),
    FakeActionAuthenticatorModule(),
    FakeKeepImportsModule()
  )
}
