package com.keepit.abook

import com.keepit.test.{ TestGlobalWithDB, TestApplicationFromGlobal, DbInjectionHelper }
import com.keepit.inject.{ EmptyInjector, TestFortyTwoModule, ApplicationInjector }
import com.google.inject.Module
import java.io.File
import com.keepit.common.healthcheck.{ FakeHealthcheckModule, FakeMemoryUsageModule, FakeAirbrakeModule }
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.db.{ TestDbInfo, TestSlickModule }
import com.keepit.common.controller.FakeActionAuthenticatorModule
import play.api.Mode
import com.google.inject.util.Modules
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ABookCacheModule }
import com.keepit.common.actor.TestSchedulerModule

class ABookApplication(overridingModules: Module*)(implicit path: File = new File("./modules/abook/"))
    extends TestApplicationFromGlobal(path, new TestGlobalWithDB(
      Seq(
        FakeAirbrakeModule(),
        FakeMemoryUsageModule(),
        FakeClockModule(),
        FakeHealthcheckModule(),
        TestFortyTwoModule(),
        FakeDiscoveryModule(),
        TestSlickModule(TestDbInfo.dbInfo),
        FakeActionAuthenticatorModule(),
        FakeABookStoreModule(),
        TestContactsUpdaterPluginModule(),
        FakeAbookRepoChangeListenerModule()
      ), overridingModules
    )) {
  println(s"[ABookApplication] path=$path canonicalPath=${path.getCanonicalPath}")
}

trait ABookApplicationInjector extends ApplicationInjector with DbInjectionHelper with ABookInjectionHelpers

trait ABookTestInjector extends EmptyInjector with DbInjectionHelper with ABookInjectionHelpers {
  val mode = Mode.Test
  val module = Modules.combine(
    FakeAirbrakeModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    ABookCacheModule(HashMapMemoryCacheModule()),
    TestSchedulerModule(),
    FakeAbookRepoChangeListenerModule()
  )
}
