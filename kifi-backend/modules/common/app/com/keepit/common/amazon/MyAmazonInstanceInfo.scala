package com.keepit.common.amazon

case class MyAmazonInstanceInfo(info: AmazonInstanceInfo) {
  if (info.instantTypeInfo == AmazonInstanceType.UNKNOWN) {
    throw new IllegalStateException(s"Unknown machine type for current instance: $info")
  }
}
