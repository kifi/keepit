package com.keepit.common.healthcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Provides
import com.keepit.inject.AppScoped
import com.keepit.common.actor.ActorInstance
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.DiscoveryModule
import scala.sys.process._
import com.keepit.common.mail.{ProdAmazonMailModule, AmazonSimpleMailProvider}

trait HealthCheckModule extends ScalaModule

case class ProdHealthCheckModule() extends HealthCheckModule {

  def configure() {
    install(new ProdAmazonMailModule())
  }

  @Provides
  @AppScoped
  def healthcheckHost(): HealthcheckHost = HealthcheckHost("hostname".!!.trim)

  @Provides
  @AppScoped
  def healthcheckProvider(actor: ActorInstance[HealthcheckActor],
    services: FortyTwoServices, host: HealthcheckHost, scheduling: SchedulingProperties, amazonSimpleMailProvider: AmazonSimpleMailProvider): HealthcheckPlugin = {
    new HealthcheckPluginImpl(actor, services, host, scheduling, DiscoveryModule.isCanary, amazonSimpleMailProvider)
  }
}
