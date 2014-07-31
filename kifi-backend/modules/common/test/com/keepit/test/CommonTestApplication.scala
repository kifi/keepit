package com.keepit.test

import java.io.File
import com.keepit.inject.{ TestFortyTwoModule }
import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.healthcheck.FakeMemoryUsageModule
import com.keepit.common.actor.FakeSchedulerModule

class CommonTestApplication(overridingModules: Module*)(implicit path: File = new File("."))
  extends TestApplication(path, overridingModules,
    Seq(
      FakeClockModule(),
      FakeHealthcheckModule(),
      FakeAirbrakeModule(),
      FakeMemoryUsageModule(),
      TestFortyTwoModule(),
      FakeDiscoveryModule(),
      FakeSchedulerModule()
    )
  )

trait CommonTestInjector extends TestInjector {
  lazy val module = Modules.combine(FakeClockModule(), FakeHealthcheckModule(), FakeAirbrakeModule())
}
