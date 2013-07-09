package com.keepit.test

import com.google.inject.Module
import com.keepit.FortyTwoGlobal

import play.api.Mode.Test
import com.google.inject.util.Modules

case class DeprecatedTestGlobal(modules: Module*) extends FortyTwoGlobal(Test) {
  val module = Modules.combine(modules:_*)

  override val initialized = true

}

case class DeprecatedTestRemoteGlobal(modules: Module*) extends FortyTwoGlobal(Test) {
  val module = Modules.combine(modules:_*)
  override val initialized = true
}



