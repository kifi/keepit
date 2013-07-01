package com.keepit.test


import play.api.Mode

import com.keepit.inject.InjectorProvider

trait TestInjector extends InjectorProvider {

  val mode = Mode.Test
  val modules = Seq(TestModule())

}
