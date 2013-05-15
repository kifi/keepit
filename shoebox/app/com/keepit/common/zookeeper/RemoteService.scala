package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.common.service._

case class RemoteService(
  amazonInstanceId: AmazonInstanceId,
  status: ServiceStatus,
  ipAddress: IpAddress,
  serviceType: ServiceType)

