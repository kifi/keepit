package com.keepit.graph

import com.keepit.FortyTwoGlobal
import com.keepit.common.healthcheck.HealthcheckPlugin
import play.api.Mode._
import play.api._
import com.keepit.graph.manager.GraphManagerPlugin

object GraphGlobal extends FortyTwoGlobal(Prod) with GraphServices {
  val module = GraphProdModule()

  override def onStart(app: Application) {
    log.info("starting graph")
    startGraphServices()
    super.onStart(app)
    log.info("graph started")
  }
}

trait GraphServices { self: FortyTwoGlobal =>
  def startGraphServices() {
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[GraphManagerPlugin] != null) //make sure its not lazy loaded
  }
}
