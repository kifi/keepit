package com.keepit.test

import java.io.File
import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ScraperCacheModule }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeMemoryUsageModule, FakeHealthcheckModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.inject.{ FakeFortyTwoModule, InjectorProvider, ApplicationInjector, EmptyInjector }
import play.api.Mode
import com.keepit.scraper.ScraperServiceTypeModule
import com.keepit.common.store.ScraperTestStoreModule

class ScraperApplication(overridingModules: Module*)(implicit path: File = new File("./modules/scraper/"))
  extends TestApplication(path, overridingModules, Seq(
    ScraperServiceTypeModule(),
    FakeHttpClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    FakeDiscoveryModule(),
    ScraperTestStoreModule(),
    ScraperCacheModule(HashMapMemoryCacheModule())
  ))

trait ScraperApplicationInjector extends TestInjectorProvider with ApplicationInjector with ScraperInjectionHelpers

trait ScraperTestInjector extends TestInjector with ScraperInjectionHelpers {
  val module = Modules.combine(
    ScraperServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    ScraperCacheModule(HashMapMemoryCacheModule())
  )
}

trait ScraperInjectionHelpers { self: TestInjectorProvider =>
}
