package com.keepit.graph.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import play.api.Application
import com.keepit.graph.GraphServices

object GraphDevGlobal extends FortyTwoGlobal(Dev) with GraphServices {
  override val module = GraphDevModule()

  override def onStart(app: Application) {
    startGraphServices()
    super.onStart(app)
  }
}
