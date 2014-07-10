package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.eliza.{ ElizaProdModule, ElizaServices }
import com.google.inject.util.Modules
import play.api.Application

object ElizaDevGlobal extends FortyTwoGlobal(Dev) with ElizaServices {
  override val module = ElizaDevModule()

  override def onStart(app: Application) {
    startElizaServices()
    super.onStart(app)
  }
}
