package com.keepit.search.test

import akka.actor.ActorSystem
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.inject.{ FakeFortyTwoModule, ApplicationInjector }
import java.io.File
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule }
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.search.common.cache.SearchCacheModule
import com.keepit.search.index.{ DevIndexModule, FakeIndexModule }
import com.keepit.search.tracking.{ DevTrackingModule, FakeTrackingModule }
import com.keepit.common.store.SearchFakeStoreModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.search.{ FakeDistributedSearchServiceClientModule, SearchConfigModule, FakeSearchServiceClientModule, SearchServiceTypeModule, FakeSearchConfigModule }
import com.keepit.test.{ TestInjectorProvider, TestInjector, TestApplication }

class SearchApplication(overridingModules: Module*)(implicit path: File = new File("./modules/search/"))
  extends TestApplication(path, overridingModules, Seq(
    FakeExecutionContextModule(),
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
    SearchCacheModule(HashMapMemoryCacheModule()),
    SearchConfigModule(),
    FakeActorSystemModule()
  ))

trait SearchApplicationInjector extends TestInjectorProvider with ApplicationInjector with SearchInjectionHelpers

trait SearchTestInjector extends TestInjector with SearchInjectionHelpers {
  implicit val system = ActorSystem("test")

  val module = Modules.combine(
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeHttpClientModule(),
    FakeUserActionsModule(),
    FakeHeimdalServiceClientModule(),
    SearchServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    FakeTrackingModule(),
    SearchFakeStoreModule(),
    FakeIndexModule(),
    FakeDiscoveryModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeDistributedSearchServiceClientModule(),
    FakeElizaServiceClientModule(),
    SearchCacheModule(HashMapMemoryCacheModule()),
    FakeSearchConfigModule()
  )
}
