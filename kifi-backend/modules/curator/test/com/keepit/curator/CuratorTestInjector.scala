package com.keepit.curator

import com.google.inject.util.Modules
import com.keepit.common.cache.{ CuratorCacheModule, HashMapMemoryCacheModule }
import com.keepit.common.db.{ TestDbInfo, TestSlickModule }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.FakeClockModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ DbInjectionHelper, TestInjector }

trait CuratorTestInjector extends TestInjector with DbInjectionHelper {
  val module = Modules.combine(
    CuratorServiceTypeModule(),
    FakeAirbrakeModule(),
    FakeClockModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    FakeShoeboxServiceModule(),
    CuratorCacheModule(HashMapMemoryCacheModule()),
    FakeHttpClientModule()
  )
}
