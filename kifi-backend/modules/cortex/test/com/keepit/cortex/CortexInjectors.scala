package com.keepit.cortex

import com.google.inject.util.Modules
import com.keepit.common.cache.{ CortexCacheModule, HashMapMemoryCacheModule }
import com.keepit.common.db.{ TestDbInfo, TestSlickModule }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.FakeClockModule
import com.keepit.inject.EmptyInjector
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ TestInjector, DbInjectionHelper }

import play.api.Mode

trait CortexTestInjector extends TestInjector with DbInjectionHelper {
  val module = Modules.combine(
    FakeAirbrakeModule(),
    FakeClockModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    FakeShoeboxServiceModule(),
    CortexCacheModule(HashMapMemoryCacheModule()),
    FakeHttpClientModule()
  )
}
