package com.keepit.test

import com.keepit.eliza.{ ElizaServiceTypeModule, FakeElizaServiceClientModule }
import com.keepit.inject.{ TestFortyTwoModule, ApplicationInjector }
import com.keepit.common.db.TestDbInfo
import java.io.File
import com.keepit.common.time.FakeClockModule
import com.keepit.common.db.TestSlickModule
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ElizaCacheModule }
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.net.FakeHttpClientModule

class ElizaApplication(overridingModules: Module*)(implicit path: File = new File("./modules/eliza/"))
  extends DbTestApplication(path, overridingModules, Seq(
    ElizaServiceTypeModule(),
    FakeHttpClientModule(),
    TestABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestFortyTwoModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    FakeDiscoveryModule(),
    ElizaCacheModule(HashMapMemoryCacheModule())
  ))

trait ElizaApplicationInjector extends ApplicationInjector with DbInjectionHelper with ElizaInjectionHelpers

trait ElizaTestInjector extends TestInjector with DbInjectionHelper with ElizaInjectionHelpers {
  val module = Modules.combine(
    ElizaServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    ElizaCacheModule(HashMapMemoryCacheModule())
  )
}
