package com.keepit.abook

import com.keepit.test.{TestGlobalWithDB, TestApplicationFromGlobal, DbInjectionHelper}
import com.keepit.inject.{TestFortyTwoModule, ApplicationInjector}
import com.google.inject.Module
import java.io.File
import com.keepit.common.healthcheck.{FakeHealthcheckModule, FakeMemoryUsageModule, FakeAirbrakeModule}
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.db.{TestDbInfo, TestSlickModule}
import com.keepit.common.controller.FakeActionAuthenticatorModule
import scala.slick.jdbc.JdbcBackend.{Database => SlickDatabase}

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
    FakeABookRawInfoStoreModule(),
    TestContactsUpdaterPluginModule()
  ), overridingModules
)) {
  println(s"[ABookApplication] path=$path canonicalPath=${path.getCanonicalPath}")
}

trait ABookApplicationInjector extends ApplicationInjector with DbInjectionHelper with ABookInjectionHelpers
