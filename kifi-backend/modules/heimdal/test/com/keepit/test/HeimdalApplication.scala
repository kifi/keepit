package com.keepit.test

import java.io.File

import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.actor.{ FakeActorSystemModule, FakeSchedulerModule }
import com.keepit.common.aws.AwsModule
import com.keepit.common.cache.{ HeimdalCacheModule, HashMapMemoryCacheModule }
import com.keepit.common.concurrent.{ FakeExecutionContextModule }
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.{ TestDbInfo, FakeSlickModule }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.HeimdalServiceTypeModule
import com.keepit.inject.{ ApplicationInjector, FakeFortyTwoModule }
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.slack.FakeSlackClientModule

class HeimdalApplication(overridingModules: Module*)(implicit path: File = new File("./modules/heimdal/"))
  extends DbTestApplication(path, overridingModules, Seq(
    FakeExecutionContextModule(),
    HeimdalServiceTypeModule(),
    FakeShoeboxServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    FakeDiscoveryModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    HeimdalCacheModule(HashMapMemoryCacheModule()),
    FakeUserActionsModule(),
    FakeSchedulerModule(),
    AwsModule(),
    FakeCryptoModule(),
    FakeSlackClientModule()
  ))

trait HeimdalApplicationInjector extends TestInjectorProvider with ApplicationInjector with DbInjectionHelper with HeimdalInjectionHelpers

trait HeimdalTestInjector extends TestInjector with DbInjectionHelper with HeimdalInjectionHelpers {
  val module = Modules.combine(
    FakeExecutionContextModule(),
    HeimdalServiceTypeModule(),
    FakeShoeboxServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    HeimdalCacheModule(HashMapMemoryCacheModule()),
    FakeSchedulerModule(),
    AwsModule(),
    FakeCryptoModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule(),
    FakeHttpClientModule()
  )
}
