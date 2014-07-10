package com.keepit.graph.test

import com.google.inject.Module
import java.io.File
import com.keepit.test.{ TestGlobal, TestApplicationFromGlobal }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.common.healthcheck.{ FakeHealthcheckModule, FakeMemoryUsageModule, FakeAirbrakeModule }
import com.keepit.common.time.FakeClockModule
import com.keepit.inject.{ EmptyInjector, ApplicationInjector, TestFortyTwoModule }
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.cache.{ HashMapMemoryCacheModule }
import play.api.Mode
import com.google.inject.util.Modules
import com.keepit.shoebox.TestShoeboxServiceClientModule
import com.keepit.graph.common.cache.GraphCacheModule

class GraphApplication(overridingModules: Module*)(implicit path: File = new File("./modules/graph/"))
  extends TestApplicationFromGlobal(path, new TestGlobal(
    Seq(
      FakeHttpClientModule(),
      TestABookServiceClientModule(),
      TestShoeboxServiceClientModule(),
      FakeElizaServiceClientModule(),
      FakeAirbrakeModule(),
      FakeMemoryUsageModule(),
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      FakeDiscoveryModule(),
      GraphCacheModule(HashMapMemoryCacheModule())
    ), overridingModules
  ))

trait GraphApplicationInjector extends ApplicationInjector

trait GraphTestInjector extends EmptyInjector {
  val mode = Mode.Test
  val module = Modules.combine(
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    GraphCacheModule(HashMapMemoryCacheModule())
  )
}
