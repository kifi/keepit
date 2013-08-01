package com.keepit.eliza

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{InMemoryCachePlugin, FortyTwoCachePlugin}
import com.keepit.common.healthcheck._
import play.api.Mode._
import play.api._

object ElizaGlobal extends FortyTwoGlobal(Prod) with ElizaServices {
  val module = ElizaProdModule()

  override def onStart(app: Application) {
    log.info("starting eliza")
    startElizaServices()
    super.onStart(app)
    log.info("eliza started")
  }

}

trait ElizaServices { self: FortyTwoGlobal =>
  def startElizaServices() {
    require(injector.instance[HealthcheckPlugin].enabled)
    require(injector.instance[FortyTwoCachePlugin].enabled)
    require(injector.instance[InMemoryCachePlugin].enabled)
  }
}
