package com.keepit.test

import java.io.File

import akka.actor.ActorSystem
import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.{ HashMapMemoryCacheModule, SearchCacheModule }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.SearchFakeStoreModule
import com.keepit.common.time.FakeClockModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.inject.{ ApplicationInjector, FakeFortyTwoModule }
import com.keepit.search.index.DevIndexModule
import com.keepit.search.spellcheck.FakeSpellCorrectorModule
import com.keepit.search.tracker.DevTrackingModule
import com.keepit.search.{ FakeSearchServiceClientModule, SearchConfigModule, SearchServiceTypeModule }
import com.keepit.shoebox.FakeShoeboxServiceModule

class SearchApplication(overridingModules: Module*)(implicit path: File = new File("./modules/search/"))
  extends TestApplication(path, overridingModules, Seq(
    SearchServiceTypeModule(),
    FakeHttpClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    DevTrackingModule(),
    SearchFakeStoreModule(),
    DevIndexModule(),
    FakeDiscoveryModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeSpellCorrectorModule(),
    SearchCacheModule(HashMapMemoryCacheModule()),
    SearchConfigModule(),
    FakeActorSystemModule()
  ))

trait SearchApplicationInjector extends ApplicationInjector with SearchInjectionHelpers

trait SearchTestInjector extends TestInjector with SearchInjectionHelpers {
  implicit val system = ActorSystem("test")

  val module = Modules.combine(
    FakeActorSystemModule(),
    FakeHttpClientModule(),
    FakeHeimdalServiceClientModule(),
    SearchServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    DevTrackingModule(),
    SearchFakeStoreModule(),
    DevIndexModule(),
    FakeDiscoveryModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeSpellCorrectorModule(),
    SearchCacheModule(HashMapMemoryCacheModule()),
    SearchConfigModule()
  )
}
