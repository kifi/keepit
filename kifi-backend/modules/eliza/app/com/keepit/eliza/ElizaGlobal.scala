package com.keepit.eliza

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ InMemoryCachePlugin, FortyTwoCachePlugin }
import com.keepit.common.healthcheck._
import com.keepit.eliza.model.MessageSequencingPlugin
import play.api.Mode._
import play.api._
import com.keepit.eliza.mail.{ MailMessageReceiverPlugin, ElizaEmailNotifierPlugin }
import com.keepit.eliza.commanders.EmailMessageProcessingCommander
import net.codingwell.scalaguice.InjectorExtensions._

object ElizaGlobal extends FortyTwoGlobal(Prod) with ElizaServices {
  val module = ElizaProdModule()

  override def onStart(app: Application) {
    log.info("starting eliza")
    startElizaServices()
    super.onStart(app)
    injector.instance[EmailMessageProcessingCommander].readIncomingMessages()
    log.info("eliza started")
  }

}

trait ElizaServices { self: FortyTwoGlobal =>
  def startElizaServices() {
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[InMemoryCachePlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[ElizaEmailNotifierPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[ElizaTasksPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[MailMessageReceiverPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[LoadBalancerCheckPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[MessageSequencingPlugin] != null) // make sure its not lazy loaded
  }
}
