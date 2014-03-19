package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.google.inject.util.Modules
import play.api.Application
import com.keepit.cortex.CortexServices

object CortexDevGlobal extends FortyTwoGlobal(Dev) with CortexServices {
  override val module = CortexDevModule()

  override def onStart(app: Application) {
    startCortexServices()
    super.onStart(app)
  }
}
