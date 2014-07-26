package com.keepit.test

import java.io.File
import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ScraperCacheModule }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeMemoryUsageModule, FakeHealthcheckModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.inject.{ TestFortyTwoModule, InjectorProvider, ApplicationInjector, EmptyInjector }
import play.api.Mode

class ScraperApplication(overridingModules: Module*)(implicit path: File = new File("./modules/scraper/"))
  extends TestApplicationFromGlobal(path, new TestGlobal(
    Seq(
      FakeHttpClientModule(),
      FakeAirbrakeModule(),
      FakeMemoryUsageModule(),
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      FakeDiscoveryModule(),
      ScraperCacheModule(HashMapMemoryCacheModule())
    ), overridingModules
  ))

trait ScraperApplicationInjector extends ApplicationInjector with ScraperInjectionHelpers

trait ScraperTestInjector extends EmptyInjector with ScraperInjectionHelpers {
  val mode = Mode.Test
  val module = Modules.combine(
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    ScraperCacheModule(HashMapMemoryCacheModule())
  )
}

trait ScraperInjectionHelpers { self: InjectorProvider =>
}
