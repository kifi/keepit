package com.keepit.curator

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ FortyTwoCachePlugin, InMemoryCachePlugin }
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.curator.model.RawSeedItemSequencingPlugin
import com.keepit.curator.commanders.SeedIngestionPlugin

import play.api.Application
import play.api.Mode.Prod

object CuratorGlobal extends FortyTwoGlobal(Prod) with CuratorServices {
  val module = CuratorProdModule()

  override def onStart(app: Application) {
    log.info("starting curator")
    startCuratorServices()
    super.onStart(app)
    log.info("curator started")
  }
}

trait CuratorServices { self: FortyTwoGlobal =>
  def startCuratorServices() {
    require(injector.instance[HealthcheckPlugin] != null)
    require(injector.instance[FortyTwoCachePlugin] != null)
    require(injector.instance[InMemoryCachePlugin] != null)
    require(injector.instance[RawSeedItemSequencingPlugin] != null)
    require(injector.instance[SeedIngestionPlugin] != null)
  }
}
