package com.keepit.test

import java.io.File
import com.keepit.inject.{ TestFortyTwoModule }
import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.zookeeper.{ ServiceTypeModule, FakeDiscoveryModule }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.healthcheck.FakeMemoryUsageModule
import com.keepit.common.actor.FakeSchedulerModule
import com.keepit.common.service.ServiceType

case class CommonServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.TEST_MODE
  val servicesToListenOn = ServiceType.inProduction
}

class CommonTestApplication(overridingModules: Module*)(implicit path: File = new File("."))
  extends TestApplication(path, overridingModules,
    Seq(
      CommonServiceTypeModule(),
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
  lazy val module = Modules.combine(CommonServiceTypeModule(), FakeClockModule(), FakeHealthcheckModule(), FakeAirbrakeModule())
}
