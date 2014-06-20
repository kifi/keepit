package com.keepit.cortex

import com.google.inject.util.Modules
import com.keepit.common.db.{TestDbInfo, TestSlickModule}
import com.keepit.common.healthcheck.{FakeAirbrakeModule, FakeHealthcheckModule}
import com.keepit.common.time.FakeClockModule
import com.keepit.inject.EmptyInjector
import com.keepit.test.DbInjectionHelper

import play.api.Mode

trait CortexTestInjector extends EmptyInjector with DbInjectionHelper {
  val mode = Mode.Test
  val module = Modules.combine(
    FakeAirbrakeModule(),
    FakeClockModule(),
    TestSlickModule(TestDbInfo.dbInfo))
}
