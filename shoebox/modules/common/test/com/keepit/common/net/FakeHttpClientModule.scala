package com.keepit.common.net

import com.tzavellas.sse.guice.ScalaModule
import com.keepit.common.amazon._
import com.keepit.common.service._
import com.google.inject._

case class FakeHttpClientModule() extends ScalaModule {

  def configure(): Unit = {
    bind[HttpClient].toInstance(new FakeHttpClient())
  }

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
