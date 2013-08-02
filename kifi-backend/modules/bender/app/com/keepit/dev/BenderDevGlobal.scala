package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.bender.{BenderProdModule, BenderServices}
import com.google.inject.util.Modules
import play.api.Application

object BenderDevGlobal extends FortyTwoGlobal(Dev) with BenderServices {
  override val module = BenderDevModule()

  override def onStart(app: Application) {
    startBenderServices()
    super.onStart(app)
  }
}
