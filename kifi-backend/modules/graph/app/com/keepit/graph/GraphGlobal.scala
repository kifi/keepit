package com.keepit.graph

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import play.api._

object GraphGlobal extends FortyTwoGlobal(Prod) with GraphServices{
  val module = GraphProdModule()

  override def onStart(app: Application) {
    log.info("starting graph")
    startGraphServices()
    super.onStart(app)
    log.info("graph started")
  }
}

trait GraphServices { self: FortyTwoGlobal =>
  def startGraphServices(){}
}
