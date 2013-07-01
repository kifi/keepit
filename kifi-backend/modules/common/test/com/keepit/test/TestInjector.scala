package com.keepit.test

import play.api.Mode
import com.keepit.inject.EmptyInjector

trait TestInjector extends EmptyInjector {
  val mode = Mode.Test
  val modules = Seq(TestModule())
}

trait SimpleTestInjector extends EmptyInjector {
  val mode = Mode.Test
  val modules = Seq(FakeClockModule())
}

trait SimpleTestDBRunner extends EmptyInjector with DbRepos {

}