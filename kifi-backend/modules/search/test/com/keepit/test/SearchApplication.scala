package com.keepit.test

import com.keepit.search.spellcheck.FakeSpellCorrectorModule
import com.keepit.inject.{ TestFortyTwoModule, ApplicationInjector }
import java.io.File
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.{ HashMapMemoryCacheModule, SearchCacheModule }
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.search.index.DevIndexModule
import com.keepit.search.tracker.DevTrackingModule
import com.keepit.common.store.{ SearchFakeStoreModule }
import com.keepit.shoebox.{ FakeShoeboxServiceModule, TestShoeboxServiceClientModule }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.search.{ SearchServiceTypeModule, TestSearchServiceClientModule, SearchConfigModule }
import com.keepit.common.actor.TestActorSystemModule

class SearchApplication(overridingModules: Module*)(implicit path: File = new File("./modules/search/"))
  extends TestApplication(path, overridingModules, Seq(
    SearchServiceTypeModule(),
    FakeHttpClientModule(),
    TestHeimdalServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestFortyTwoModule(),
    DevTrackingModule(),
    SearchFakeStoreModule(),
    DevIndexModule(),
    FakeDiscoveryModule(),
    FakeShoeboxServiceModule(),
    TestSearchServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeSpellCorrectorModule(),
    SearchCacheModule(HashMapMemoryCacheModule()),
    SearchConfigModule(),
    TestActorSystemModule()
  ))

trait SearchApplicationInjector extends ApplicationInjector with SearchInjectionHelpers

trait SearchTestInjector extends TestInjector with SearchInjectionHelpers {
  val module = Modules.combine(
    SearchServiceTypeModule(),
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
