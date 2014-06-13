package com.keepit.common.amazon

import com.keepit.common.service.ServiceType

case class MyInstanceInfo(info: AmazonInstanceInfo, serviceType: ServiceType) {
  if (info.instantTypeInfo == AmazonInstanceType.UNKNOWN) {
    throw new IllegalStateException(s"Unknown machine type for current instance: $info")
  }
}
