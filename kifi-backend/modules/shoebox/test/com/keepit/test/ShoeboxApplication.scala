package com.keepit.test

import java.io.File

import com.google.inject.{ Injector, Module }
import com.google.inject.util.Modules
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, FakeSchedulerModule }
import com.keepit.common.aws.AwsModule
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ FakeSecureSocialClientIdModule, FakeUserActionsModule }
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.{ TestDbInfo, FakeSlickModule }

import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.oauth.{ FakeOAuth1ConfigurationModule, FakeOAuth2ConfigurationModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.inject.{ ApplicationInjector, FakeFortyTwoModule }
import com.keepit.normalizer.FakeNormalizationServiceModule
import com.keepit.notify.FakeShoeboxNotifyModule
import com.keepit.queue.FakeNormalizationUpdateJobQueueModule
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox._

class ShoeboxApplication(overridingModules: Module*)(implicit path: File = new File("./modules/shoebox/"))
  extends DbTestApplication(path, overridingModules, Seq(
    ShoeboxServiceTypeModule(),
    FakeOAuth1ConfigurationModule(),
    FakeOAuth2ConfigurationModule(),
    FakeABookServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    FakeDiscoveryModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    FakeNormalizationServiceModule(),
    FakeSecureSocialClientIdModule(),
    FakeUserActionsModule(),
    FakeHttpClientModule(),
    AbuseControlModule(),
    FakeSchedulerModule(),
    FakeKeepImportsModule(),
    FakeNormalizationUpdateJobQueueModule(),
    AwsModule(),
    FakeCryptoModule(),
    DevTwilioCredentialsModule(),
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeShoeboxNotifyModule()
  ))

trait ShoeboxApplicationInjector extends TestInjectorProvider with ApplicationInjector with DbInjectionHelper with ShoeboxInjectionHelpers

trait ShoeboxTestInjector extends TestInjector with DbInjectionHelper with ShoeboxInjectionHelpers {
  val module = Modules.combine(
    ShoeboxServiceTypeModule(),
    FakeOAuth1ConfigurationModule(),
    FakeOAuth2ConfigurationModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    FakeNormalizationServiceModule(),
    AbuseControlModule(),
    FakeSchedulerModule(),
    FakeNormalizationUpdateJobQueueModule(),
    AwsModule(),
    FakeCryptoModule(),
    FakeActorSystemModule(),
    FakeSecureSocialClientIdModule(),
    FakeUserActionsModule(),
    FakeKeepImportsModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeHttpClientModule(),
    DevTwilioCredentialsModule(),
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeShoeboxNotifyModule()
  )

  def testFactory(implicit injector: Injector) = inject[ShoeboxTestFactory]
}
