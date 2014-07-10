package com.keepit.maven

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ FortyTwoCachePlugin, InMemoryCachePlugin }
import com.keepit.common.healthcheck.HealthcheckPlugin
import play.api.Application
import play.api.Mode.Prod

object MavenGlobal extends FortyTwoGlobal(Prod) with MavenServices {
  val module = MavenProdModule()

  override def onStart(app: Application) {
    log.info("starting maven")
    startMavenServices()
    super.onStart(app)
    log.info("maven started")
  }
}

trait MavenServices { self: FortyTwoGlobal =>
  def startMavenServices() {
    require(injector.instance[HealthcheckPlugin] != null)
    require(injector.instance[FortyTwoCachePlugin] != null)
    require(injector.instance[InMemoryCachePlugin] != null)
  }
}
