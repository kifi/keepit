package com.keepit.heimdal

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{InMemoryCachePlugin, FortyTwoCachePlugin}
import com.keepit.common.healthcheck._
import play.api.Mode._
import play.api._

object HeimdalGlobal extends FortyTwoGlobal(Prod) with HeimdalServices {
  val module = HeimdalProdModule()

  override def onStart(app: Application) {
    log.info("starting heimdal")
    startHeimdalServices()
    super.onStart(app)
    log.info("heimdal started")
  }

}

trait HeimdalServices { self: FortyTwoGlobal =>
  def startHeimdalServices() {
    require(injector.instance[HealthcheckPlugin].enabled)
    require(injector.instance[FortyTwoCachePlugin].enabled)
    require(injector.instance[InMemoryCachePlugin].enabled)
  }
}
