package com.keepit.shoebox

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.common.service.ServiceClient
import com.keepit.common.zookeeper.ServiceCluster


trait ShoeboxScraperClient extends ServiceClient {
}

class ShoeboxScraperClientImpl @Inject() (
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier)
  extends ShoeboxScraperClient with Logging {

}
