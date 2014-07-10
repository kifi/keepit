package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.abook.{ ABookProdModule, ABookServices }
import com.google.inject.util.Modules
import play.api.Application

object ABookDevGlobal extends FortyTwoGlobal(Dev) with ABookServices {
  override val module = ABookDevModule()

  override def onStart(app: Application) {
    startABookServices()
    super.onStart(app)
  }
}
