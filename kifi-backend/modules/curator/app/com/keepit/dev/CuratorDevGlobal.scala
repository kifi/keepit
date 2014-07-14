package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.google.inject.util.Modules
import play.api.Application
import com.keepit.curator.CuratorServices

object CuratorDevGlobal extends FortyTwoGlobal(Dev) with CuratorServices {
  override val module = CuratorDevModule()

  override def onStart(app: Application) {
    startCuratorServices()
    super.onStart(app)
  }
}
