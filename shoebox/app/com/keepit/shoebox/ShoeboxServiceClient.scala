package com.keepit.shoebox

import com.keepit.common.controller.{ServiceClient, ServiceType}
import com.keepit.common.net.HttpClient

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX
}
class ShoeboxServiceClientImpl(override val host: String, override val port: Int, override val httpClient: HttpClient)
    extends ShoeboxServiceClient

