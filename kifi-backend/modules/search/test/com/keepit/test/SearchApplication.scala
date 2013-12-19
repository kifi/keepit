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
import com.keepit.search.{DevTrackingModule, DevIndexModule}
import com.keepit.common.store.SearchDevStoreModule
import com.keepit.shoebox.TestShoeboxServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule

class SearchApplication(overridingModules: Module*)(implicit path: File = new File("./modules/search/"))
  extends TestApplicationFromGlobal(path, new TestGlobal(
    Seq(
      FakeHttpClientModule(),
      TestHeimdalServiceClientModule(),
      FakeAirbrakeModule(),
      FakeMemoryUsageModule(),
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      DevTrackingModule(),
      SearchDevStoreModule(),
      DevIndexModule(),
      FakeDiscoveryModule(),
      TestShoeboxServiceClientModule(),
      FakeElizaServiceClientModule(),
      FakeSpellCorrectorModule(),
      SearchCacheModule(HashMapMemoryCacheModule())
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
    SearchDevStoreModule(),
    DevIndexModule(),
    TestShoeboxServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeSpellCorrectorModule(),
    SearchCacheModule(HashMapMemoryCacheModule())
  )
}
