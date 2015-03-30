package com.keepit.test

import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.eliza.{ ElizaServiceTypeModule, FakeElizaServiceClientModule }
import com.keepit.inject.{ FakeFortyTwoModule, ApplicationInjector }
import com.keepit.common.db.TestDbInfo
import java.io.File
import com.keepit.common.time.FakeClockModule
import com.keepit.common.db.FakeSlickModule
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ElizaCacheModule }
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.realtime.FakeUrbanAirshipModule

class ElizaApplication(overridingModules: Module*)(implicit path: File = new File("./modules/eliza/"))
  extends DbTestApplication(path, overridingModules, Seq(
    ElizaServiceTypeModule(),
    FakeHttpClientModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    FakeDiscoveryModule(),
    ElizaCacheModule(HashMapMemoryCacheModule())
  ))

trait ElizaApplicationInjector extends TestInjectorProvider with ApplicationInjector with DbInjectionHelper with ElizaInjectionHelpers

trait ElizaTestInjector extends TestInjector with DbInjectionHelper with ElizaInjectionHelpers {
  val module = Modules.combine(
    FakeUrbanAirshipModule(),
    ElizaServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeUserActionsModule(),
    FakeHttpClientModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    ElizaCacheModule(HashMapMemoryCacheModule())
  )
}
