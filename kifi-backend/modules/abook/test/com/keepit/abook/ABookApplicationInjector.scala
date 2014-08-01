package com.keepit.abook

import com.keepit.test.{ TestInjector, DbTestApplication, DbInjectionHelper }
import com.keepit.inject.{ FakeFortyTwoModule, ApplicationInjector }
import com.google.inject.Module
import java.io.File
import com.keepit.common.healthcheck.{ FakeHealthcheckModule, FakeMemoryUsageModule, FakeAirbrakeModule }
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.db.{ TestDbInfo, FakeSlickModule }
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.google.inject.util.Modules
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ABookCacheModule }
import com.keepit.common.actor.FakeSchedulerModule

class ABookApplication(overridingModules: Module*)(implicit path: File = new File("./modules/abook/"))
    extends DbTestApplication(path, overridingModules, Seq(
      ABookServiceTypeModule(),
      FakeAirbrakeModule(),
      FakeMemoryUsageModule(),
      FakeClockModule(),
      FakeHealthcheckModule(),
      FakeFortyTwoModule(),
      FakeDiscoveryModule(),
      FakeSlickModule(TestDbInfo.dbInfo),
      FakeActionAuthenticatorModule(),
      FakeABookStoreModule(),
      FakeABookImporterPluginModule(),
      FakeAbookRepoChangeListenerModule()
    )) {
  println(s"[ABookApplication] path=$path canonicalPath=${path.getCanonicalPath}")
}

trait ABookApplicationInjector extends ApplicationInjector with DbInjectionHelper with ABookInjectionHelpers

trait ABookTestInjector extends TestInjector with DbInjectionHelper with ABookInjectionHelpers {
  val module = Modules.combine(
    ABookServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    ABookCacheModule(HashMapMemoryCacheModule()),
    FakeSchedulerModule(),
    FakeAbookRepoChangeListenerModule()
  )
}
