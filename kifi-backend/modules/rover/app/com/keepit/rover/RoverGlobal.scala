package com.keepit.rover

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import play.api._

object RoverGlobal extends FortyTwoGlobal(Prod) with RoverServices {
  val module = RoverProdModule()

  override def onStart(app: Application) {
    log.info("starting rover")
    startRoverServices()
    super.onStart(app)
    log.info("rover started")
  }
}

trait RoverServices { self: FortyTwoGlobal =>
  def startRoverServices() {}
}
