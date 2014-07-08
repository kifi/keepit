package com.keepit.test

import com.keepit.search.spellcheck.FakeSpellCorrectorModule
import com.keepit.common.controller._
import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
import play.api.{Application, Mode}
import com.keepit.inject.{TestFortyTwoModule, ApplicationInjector, EmptyInjector}
import java.io.File
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.{FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule}
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.{HashMapMemoryCacheModule, SearchCacheModule}
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.search.index.DevIndexModule
import com.keepit.search.tracker.DevTrackingModule
import com.keepit.common.store.{SearchFakeStoreModule, SearchDevStoreModule}
import com.keepit.shoebox.TestShoeboxServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.search.{TestSearchServiceClientModule, SearchConfigModule}

class SearchApplication(overridingModules: Module*)(implicit path: File = new File("./modules/search/"))
  extends TestApplicationFromGlobal(path, new TestGlobal(
    Seq(
      FakeHttpClientModule(),
      FakeAirbrakeModule(),
      FakeMemoryUsageModule(),
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      DevTrackingModule(),
      SearchFakeStoreModule(),
      DevIndexModule(),
      FakeDiscoveryModule(),
      TestShoeboxServiceClientModule(),
      TestSearchServiceClientModule(),
      FakeElizaServiceClientModule(),
      FakeSpellCorrectorModule(),
      SearchCacheModule(HashMapMemoryCacheModule()),
      SearchConfigModule()
    ), overridingModules
  ))

trait SearchApplicationInjector extends ApplicationInjector with SearchInjectionHelpers

trait SearchTestInjector extends EmptyInjector with SearchInjectionHelpers {
  val mode = Mode.Test
  val module = Modules.combine(
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    DevTrackingModule(),
    SearchFakeStoreModule(),
    DevIndexModule(),
    TestShoeboxServiceClientModule(),
    TestSearchServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeSpellCorrectorModule(),
    SearchCacheModule(HashMapMemoryCacheModule()),
    SearchConfigModule()
  )
}
