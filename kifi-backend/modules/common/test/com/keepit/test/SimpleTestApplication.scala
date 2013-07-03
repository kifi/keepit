package com.keepit.test

import java.io.File
import com.keepit.FortyTwoGlobal
import com.keepit.inject.{EmptyInjector, ApplicationInjector}
import com.google.inject.Module
import play.api.Mode._
import com.google.inject.util.Modules
import play.api.Mode
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.FakeHealthcheckModule

class SimpleTestApplication(override val path: File, _global: FortyTwoGlobal)
  extends play.api.test.FakeApplication(path = path) with ApplicationInjector {
  override lazy val global = _global
  override val mode = _global.mode
}

class SimpleTestGlobal(defaultModules: Seq[Module], overridingModules: Seq[Module]) extends FortyTwoGlobal(Test) {
  val modules = Seq(Modules.`override`(defaultModules:_*).`with`(overridingModules: _*))
  override val initialized = true
}

class RemoteTestApplication(path: File = new File("."))(overridingModules: Module*)
  extends SimpleTestApplication(path, new SimpleTestGlobal(Seq(FakeClockModule(), FakeHealthcheckModule()), overridingModules))

trait SimpleTestInjector extends EmptyInjector {
  val mode = Mode.Test
  val modules = Seq(FakeClockModule(), FakeHealthcheckModule())
}