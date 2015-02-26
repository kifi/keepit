package com.keepit.rover

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.common.service.{ ServiceType, ServiceClient }
import com.keepit.common.zookeeper.ServiceCluster
import play.api.Mode.Mode

trait RoverServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ROVER
}

class RoverServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  cacheProvider: RoverCacheProvider,
  mode: Mode) extends RoverServiceClient with Logging

case class RoverCacheProvider @Inject() ()