package com.keepit.test

import java.io.File
import com.keepit.FortyTwoGlobal
import com.google.inject.Module
import play.api.Mode._
import com.google.inject.util.Modules
import com.keepit.inject.EmptyInjector
import play.api.Mode

private[test] class TestApplicationFromGlobal(override val path: File, _global: FortyTwoGlobal)
    extends play.api.test.FakeApplication(path = path) {
  override lazy val global = _global
}

private[test] class TestGlobal(defaultModules: Seq[Module], overridingModules: Seq[Module]) extends FortyTwoGlobal(Test) {
  val module = Modules.`override`(defaultModules: _*).`with`(overridingModules: _*)
  override val initialized = true
}

class TestApplication(path: File, overridingModules: Seq[Module], defaultModules: Seq[Module])
  extends TestApplicationFromGlobal(path, new TestGlobal(defaultModules, overridingModules))

trait TestInjector extends EmptyInjector with TestInjectorProvider {
  val mode = Mode.Test
}
