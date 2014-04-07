package com.keepit.cortex

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import play.api._
import com.keepit.cortex.plugins.LDAURIFeatureUpdatePlugin

object CortexGlobal extends FortyTwoGlobal(Prod) with CortexServices{
  val module = CortexProdModule()

  override def onStart(app: Application) {
    log.info("\n\n============================\n\nstarting cortex")
    startCortexServices()
    super.onStart(app)
    log.info("cortex started")
  }
}

trait CortexServices { self: FortyTwoGlobal =>
  def startCortexServices(){
    require(injector.instance[LDAURIFeatureUpdatePlugin] != null)
  }
}
