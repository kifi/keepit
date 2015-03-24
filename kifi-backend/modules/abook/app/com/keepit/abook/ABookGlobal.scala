package com.keepit.abook

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ InMemoryCachePlugin, FortyTwoCachePlugin }
import com.keepit.common.healthcheck._
import play.api.Mode._
import play.api._
import com.keepit.abook.model.{ EContactSequencingPlugin, EmailAccountSequencingPlugin }
import com.keepit.abook.commanders.{ LocalRichConnectionCommander, EmailAccountUpdaterPlugin }
import net.codingwell.scalaguice.InjectorExtensions._

object ABookGlobal extends FortyTwoGlobal(Prod) with ABookServices {
  val module = ABookProdModule()

  override def onStart(app: Application) {
    startABookServices()
    super.onStart(app)
    injector.instance[LocalRichConnectionCommander].startUpdateProcessing()
  }

}

trait ABookServices { self: FortyTwoGlobal =>
  def startABookServices() {
    require(injector.instance[EmailAccountUpdaterPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[ABookImporterPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[InMemoryCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[EmailAccountSequencingPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[EContactSequencingPlugin] != null) //make sure its not lazy loaded
  }
}
