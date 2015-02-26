package com.keepit.rover

import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier

class FakeRoverServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier) extends RoverServiceClient
