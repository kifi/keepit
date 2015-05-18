package com.keepit.common.db

import com.keepit.test.{ TestInjector, DbInjectionHelper }
import com.google.inject.util.Modules
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.{ FakeAirbrakeAndFormatterModule, FakeAirbrakeModule, FakeHealthcheckModule }

private[db] trait SqlDbTestInjector extends TestInjector with DbInjectionHelper {
  val module = Modules.combine(FakeClockModule(), FakeHealthcheckModule(), FakeSlickModule(TestDbInfo.dbInfo), FakeAirbrakeAndFormatterModule())
}
