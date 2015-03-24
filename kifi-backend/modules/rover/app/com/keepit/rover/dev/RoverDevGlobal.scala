package com.keepit.rover.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import play.api.Application
import com.keepit.rover.RoverServices

object RoverDevGlobal extends FortyTwoGlobal(Dev) with RoverServices {
  val module = RoverDevModule()

  override def onStart(app: Application) {
    startRoverServices()
    super.onStart(app)
  }
}
