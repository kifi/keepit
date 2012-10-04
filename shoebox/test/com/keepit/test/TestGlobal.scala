package com.keepit.test

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Module
import com.keepit.FortyTwoGlobal
import play.api.Mode.Test

case class TestGlobal(module: Module) extends FortyTwoGlobal(Test) {

  override lazy val injector: Injector = Guice.createInjector(module)

}


