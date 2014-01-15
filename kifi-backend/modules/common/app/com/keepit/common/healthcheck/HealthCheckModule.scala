package com.keepit.common.healthcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Provides
import com.keepit.inject.AppScoped
import java.net.InetAddress
import com.keepit.common.actor.ActorInstance
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.DiscoveryModule

trait HealthCheckModule extends ScalaModule

case class ProdHealthCheckModule() extends HealthCheckModule {

  def configure() {}

  @Provides
  @AppScoped
  def healthcheckHost(): HealthcheckHost = HealthcheckHost(InetAddress.getLocalHost.getHostName)

  @Provides
  @AppScoped
  def healthcheckProvider(actor: ActorInstance[HealthcheckActor],
    services: FortyTwoServices, host: HealthcheckHost, scheduling: SchedulingProperties): HealthcheckPlugin = {
    new HealthcheckPluginImpl(actor, services, host, scheduling, isCanary = DiscoveryModule.isCanary)
  }
}
