package com.keepit.heimdal

import com.google.inject.Provides
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import com.keepit.common.actor.ActorInstance
import com.keepit.common.time.Clock
import com.keepit.inject.AppScoped


import play.api.Play._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.plugin.SchedulingProperties

trait HeimdalServiceClientModule extends ScalaModule

case class ProdHeimdalServiceClientModule() extends HeimdalServiceClientModule {

  def configure() {
    install(HeimdalQueueProdModule())
  }

  @Provides
  @AppScoped
  def heimdalServiceClient (
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier,
    actor: ActorInstance[HeimdalClientActor],
    clock: Clock,
    scheduling: SchedulingProperties): HeimdalServiceClient = {

    val heimdal = new HeimdalServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.HEIMDAL),
      actor,
      clock,
      scheduling
    )

    if (!heimdal.enabled){
      heimdal.onStart()
    }

    heimdal

  }
}
