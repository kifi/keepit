package com.keepit.test

import play.api.Mode
import com.keepit.inject.EmptyInjector

trait TestInjector extends EmptyInjector {
  val mode = Mode.Test
  val modules = Seq(TestModule())
}
