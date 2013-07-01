package com.keepit.common.healthcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Provides
import com.keepit.inject.AppScoped
import java.net.InetAddress
import com.keepit.common.actor.ActorFactory
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.plugin.SchedulingProperties

trait HealthCheckModule extends ScalaModule

case class HealthCheckProdModule() extends HealthCheckModule {

  def configure() {}

  @Provides
  @AppScoped
  def healthcheckHost(): HealthcheckHost = HealthcheckHost(InetAddress.getLocalHost().getHostName())

  @Provides
  @AppScoped
  def healthcheckProvider(actorFactory: ActorFactory[HealthcheckActor],
    services: FortyTwoServices, host: HealthcheckHost, schedulingProperties: SchedulingProperties): HealthcheckPlugin = {
    new HealthcheckPluginImpl(actorFactory, services, host, schedulingProperties)
  }
}