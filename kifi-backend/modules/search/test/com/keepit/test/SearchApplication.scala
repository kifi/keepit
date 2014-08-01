package com.keepit.test

import akka.actor.ActorSystem
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.search.spellcheck.FakeSpellCorrectorModule
import com.keepit.inject.{ FakeFortyTwoModule, ApplicationInjector }
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
import com.keepit.search.tracker.{ DevTrackingModule, FakeTrackingModule }
import com.keepit.common.store.SearchFakeStoreModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.eliza.FakeElizaServiceClientModule

import com.keepit.common.actor.FakeActorSystemModule

import com.keepit.search.{ SearchServiceTypeModule, TestSearchServiceClientModule, SearchConfigModule, FakeSearchConfigModule }

class SearchApplication(overridingModules: Module*)(implicit path: File = new File("./modules/search/"))
  extends TestApplication(path, overridingModules, Seq(
    SearchServiceTypeModule(),
    FakeHttpClientModule(),
    TestHeimdalServiceClientModule(),
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
    TestSearchServiceClientModule(),
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
    StandaloneTestActorSystemModule(),
    FakeHttpClientModule(),
    TestHeimdalServiceClientModule(),
    SearchServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    FakeTrackingModule(),
    SearchFakeStoreModule(),
    DevIndexModule(),
    FakeDiscoveryModule(),
    FakeShoeboxServiceModule(),
    TestSearchServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeSpellCorrectorModule(),
    SearchCacheModule(HashMapMemoryCacheModule()),
    FakeSearchConfigModule()
  )
}
