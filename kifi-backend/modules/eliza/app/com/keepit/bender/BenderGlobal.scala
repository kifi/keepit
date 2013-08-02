package com.keepit.bender

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{InMemoryCachePlugin, FortyTwoCachePlugin}
import com.keepit.common.healthcheck._
import play.api.Mode._
import play.api._

object BenderGlobal extends FortyTwoGlobal(Prod) with BenderServices {
  val module = BenderProdModule()

  override def onStart(app: Application) {
    log.info("starting bender")
    startBenderServices()
    super.onStart(app)
    log.info("bender started")
  }

}

trait BenderServices { self: FortyTwoGlobal =>
  def startBenderServices() {
    require(injector.instance[HealthcheckPlugin].enabled)
    require(injector.instance[FortyTwoCachePlugin].enabled)
    require(injector.instance[InMemoryCachePlugin].enabled)
  }
}
