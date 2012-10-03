package com.keepit.dev

import play.api.Mode._
import com.keepit.FortyTwoGlobal
import com.keepit.inject._
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage

object DevGlobal extends FortyTwoGlobal(Dev) {
  
  override lazy val injector: Injector = Guice.createInjector(Stage.DEVELOPMENT, DevModule())
  
}