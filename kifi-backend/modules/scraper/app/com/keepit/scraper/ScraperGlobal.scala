package com.keepit.scraper

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{InMemoryCachePlugin, FortyTwoCachePlugin}
import com.keepit.common.healthcheck._
import play.api.Mode._
import play.api._

object ScraperGlobal extends FortyTwoGlobal(Prod) with ScraperServices {
  val module = ProdScraperServiceModule()

  override def onStart(app: Application) {
    startScraperServices()
    super.onStart(app)
  }

}

trait ScraperServices { self: FortyTwoGlobal =>
  def startScraperServices() {
    log.info("starting ScraperService")
    // TODO: clean-up
    require(injector.instance[HealthcheckPlugin] != null)//make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null)//make sure its not lazy loaded
    require(injector.instance[InMemoryCachePlugin] != null)//make sure its not lazy loaded
    require(injector.instance[PullerPlugin] != null)
    log.info("ScraperService started")
  }


}
