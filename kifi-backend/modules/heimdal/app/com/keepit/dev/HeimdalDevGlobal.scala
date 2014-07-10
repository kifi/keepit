package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.heimdal.{ HeimdalProdModule, HeimdalServices }
import com.google.inject.util.Modules
import play.api.Application

object HeimdalDevGlobal extends FortyTwoGlobal(Dev) with HeimdalServices {
  override val module = HeimdalDevModule()

  override def onStart(app: Application) {
    startHeimdalServices()
    super.onStart(app)
  }
}
