package com.keepit.abook

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{InMemoryCachePlugin, FortyTwoCachePlugin}
import com.keepit.common.healthcheck._
import play.api.Mode._
import play.api._

object ABookGlobal extends FortyTwoGlobal(Prod) with ABookServices {
  val module = ABookProdModule()

  override def onStart(app: Application) {
    log.info("starting abook")
    startABookServices()
    super.onStart(app)
    log.info("abook started")
  }

}

trait ABookServices { self: FortyTwoGlobal =>
  def startABookServices() {
    require(injector.instance[ContactsUpdaterPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[InMemoryCachePlugin] != null) //make sure its not lazy loaded
  }
}
