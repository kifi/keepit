package com.keepit.test

import java.io.File
import com.keepit.FortyTwoGlobal
import com.keepit.inject.{TestFortyTwoModule, EmptyInjector}
import com.google.inject.Module
import play.api.Mode._
import com.google.inject.util.Modules
import play.api.Mode
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.zookeeper.FakeDiscoveryModule

class TestApplicationFromGlobal(override val path: File, _global: FortyTwoGlobal)
  extends play.api.test.FakeApplication(path = path) {
  override lazy val global = _global
}

class TestGlobal(defaultModules: Seq[Module], overridingModules: Seq[Module]) extends FortyTwoGlobal(Test) {
  val module = Modules.`override`(defaultModules:_*).`with`(overridingModules: _*)
  override val initialized = true
}

class TestApplication(overridingModules: Module*)(implicit path: File = new File("."))
  extends TestApplicationFromGlobal(path, new TestGlobal(
    Seq(
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      FakeDiscoveryModule()
    ), overridingModules
  ))


trait TestInjector extends EmptyInjector {
  val mode = Mode.Test
  lazy val module = Modules.combine(FakeClockModule(), FakeHealthcheckModule())
}
