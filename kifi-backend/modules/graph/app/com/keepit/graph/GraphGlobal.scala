package com.keepit.graph

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import play.api._

object GraphGlobal extends FortyTwoGlobal(Prod) with GraphServices {
  val module = ???

  override def onStart(app: Application) {
    log.info("starting graph engine")
    startGraphServices()
    super.onStart(app)
    log.info("graph engine started")
  }

}

trait GraphServices { self: FortyTwoGlobal =>
  def startGraphServices() {
  }
}
