package com.keepit.common.healthcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Provides
import com.keepit.inject.AppScoped
import com.keepit.common.actor.ActorInstance
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.DiscoveryModule
import scala.sys.process._
import com.keepit.common.mail.{ ProdAmazonMailModule, AmazonSimpleMailProvider }
import play.api.Play

trait HealthCheckModule extends ScalaModule

case class ProdHealthCheckModule() extends HealthCheckModule {

  def configure() {
    install(new ProdAmazonMailModule())
    bind[LoadBalancerCheckPlugin].to[LoadBalancerCheckPluginImpl].in[AppScoped]
  }

  @Provides
  @AppScoped
  def healthCheckConf(): HealthCheckConf = {
    HealthCheckConf(Play.current.configuration.getInt("healthcheck.startup.sleep").getOrElse(45))
  }

  @Provides
  @AppScoped
  def healthcheckHost(): HealthcheckHost = HealthcheckHost("hostname".!!.trim)

  @Provides
  @AppScoped
  def healthcheckProvider(actor: ActorInstance[HealthcheckActor],
    services: FortyTwoServices, host: HealthcheckHost, scheduling: SchedulingProperties, amazonSimpleMailProvider: AmazonSimpleMailProvider, healthCheckConf: HealthCheckConf): HealthcheckPlugin = {
    new HealthcheckPluginImpl(actor, services, host, scheduling, DiscoveryModule.isCanary, amazonSimpleMailProvider, healthCheckConf)
  }
}
