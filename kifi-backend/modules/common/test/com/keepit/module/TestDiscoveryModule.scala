package com.keepit.module

import com.google.inject.{Provides, Singleton}
import com.keepit.common.amazon.{AmazonInstanceId, AmazonInstanceInfo}
import com.keepit.common.service._
import com.keepit.common.zookeeper._

case class TestDiscoveryModule() extends DiscoveryModule {

  def configure() {}

  @Provides
  @Singleton
  def serviceCluster(amazonInstanceInfo: AmazonInstanceInfo): ServiceCluster =
    new ServiceCluster(ServiceType.TEST_MODE).register(Node("TEST"), amazonInstanceInfo)

  @Singleton
  @Provides
  def amazonInstanceInfo: AmazonInstanceInfo =
    new AmazonInstanceInfo(
      instanceId = AmazonInstanceId("i-f168c1a8"),
      localHostname = "ip-10-160-95-26.us-west-1.compute.internal",
      publicHostname = "ec2-50-18-183-73.us-west-1.compute.amazonaws.com",
      localIp = IpAddress("10.160.95.26"),
      publicIp = IpAddress("50.18.183.73"),
      instanceType = "c1.medium",
      availabilityZone = "us-west-1b",
      securityGroups = "default",
      amiId = "ami-1bf9de5e",
      amiLaunchIndex = "0"
    )
}
