package com.keepit.test

import java.io.File
import com.keepit.FortyTwoGlobal
import com.keepit.inject.EmptyInjector
import com.google.inject.Module
import play.api.Mode._
import com.google.inject.util.Modules
import play.api.Mode
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.FakeHealthcheckModule

class SimpleTestApplication(override val path: File, _global: FortyTwoGlobal)
  extends play.api.test.FakeApplication(path = path) {
  override lazy val global = _global
}

class TestGlobal(defaultModules: Seq[Module], overridingModules: Seq[Module]) extends FortyTwoGlobal(Test) {
  val module = Modules.`override`(defaultModules:_*).`with`(overridingModules: _*)
  override val initialized = true
}

class RemoteTestApplication(path: File = new File("."))(overridingModules: Module*)
  extends SimpleTestApplication(path, new TestGlobal(Seq(FakeClockModule(), FakeHealthcheckModule()), overridingModules))

trait TestInjector extends EmptyInjector {
  val mode = Mode.Test
  val defaultModules = Seq(FakeClockModule(), FakeHealthcheckModule())
  val specificationModules: Seq[Module] = Seq.empty
  lazy val module = Modules.`override`(defaultModules:_*).`with`(specificationModules: _*)
}