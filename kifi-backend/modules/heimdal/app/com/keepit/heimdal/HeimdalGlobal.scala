package com.keepit.heimdal

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ InMemoryCachePlugin, FortyTwoCachePlugin }
import com.keepit.common.healthcheck._
import com.keepit.helprank.ReKeepStatsUpdaterPlugin
import play.api.Mode._
import play.api._
import com.keepit.controllers.EventTrackingController
import net.codingwell.scalaguice.InjectorExtensions._

object HeimdalGlobal extends FortyTwoGlobal(Prod) with HeimdalServices {
  val module = HeimdalProdModule()

  override def onStart(app: Application) {
    log.info("starting heimdal")
    startHeimdalServices()
    super.onStart(app)
    injector.instance[EventTrackingController].readIncomingEvent()
    log.info("heimdal started")
  }

}

trait HeimdalServices { self: FortyTwoGlobal =>
  def startHeimdalServices() {
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[InMemoryCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[ReKeepStatsUpdaterPlugin] != null) //make sure its not lazy loaded
  }
}
