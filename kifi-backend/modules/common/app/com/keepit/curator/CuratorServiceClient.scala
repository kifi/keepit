package com.keepit.curator

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClient, CallTimeouts }
import com.keepit.common.routes.Curator
import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait CuratorServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CURATOR

}

class CuratorServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

}
