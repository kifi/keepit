package com.keepit.rover

import com.keepit.FortyTwoGlobal
import com.keepit.rover.manager.RoverManagerPlugin
import com.keepit.rover.model.ArticleInfoSequencingPlugin
import com.keepit.rover.tagcloud.TagCloudPlugin
import play.api.Mode._
import play.api._
import net.codingwell.scalaguice.InjectorExtensions._

object RoverGlobal extends FortyTwoGlobal(Prod) with RoverServices {
  val module = RoverProdModule()

  override def onStart(app: Application) {
    log.info("starting rover")
    startRoverServices()
    super.onStart(app)
    log.info("rover started")
  }
}

trait RoverServices { self: FortyTwoGlobal =>
  def startRoverServices(): Unit = {
    require(injector.instance[RoverManagerPlugin] != null)
    require(injector.instance[ArticleInfoSequencingPlugin] != null)
    require(injector.instance[TagCloudPlugin] != null)
  }
}
