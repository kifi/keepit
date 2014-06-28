package com.keepit.maven

import com.google.inject.util.Modules
import com.keepit.common.cache.{MavenCacheModule, HashMapMemoryCacheModule}
import com.keepit.common.db.{TestDbInfo, TestSlickModule}
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.FakeClockModule
import com.keepit.inject.EmptyInjector
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.DbInjectionHelper

import play.api.Mode

trait MavenTestInjector extends EmptyInjector with DbInjectionHelper {
  val mode = Mode.Test
  val module = Modules.combine(
      FakeAirbrakeModule(),
      FakeClockModule(),
      TestSlickModule(TestDbInfo.dbInfo),
      FakeShoeboxServiceModule(),
      MavenCacheModule(HashMapMemoryCacheModule()),
      FakeHttpClientModule()
    )
}
