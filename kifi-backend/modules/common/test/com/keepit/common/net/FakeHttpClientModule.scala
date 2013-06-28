package com.keepit.common.net

import com.keepit.common.amazon._
import com.keepit.common.service._
import com.google.inject._
import net.codingwell.scalaguice.ScalaModule

case class FakeHttpClientModule(requestToResponse: PartialFunction[String, FakeClientResponse]) extends ScalaModule {

  def configure(): Unit = {
    bind[HttpClient].toInstance(new FakeHttpClient(Some(requestToResponse)))
  }

}

case class FakeAmazonInstanceInfoModule() extends ScalaModule {
  def configure(): Unit = {}

  @Singleton
  @Provides
  def amazonInstanceInfo(httpClient: HttpClient): AmazonInstanceInfo = {
    val metadataUrl: String = "http://169.254.169.254/latest/meta-data/"

    AmazonInstanceInfo(
      instanceId = AmazonInstanceId(httpClient.get(metadataUrl + "instance-id").body),
      localHostname = "localhost",
      publicHostname = "localhost",
      localIp = IpAddress("127.0.0.1"),
      publicIp = IpAddress("127.0.0.1"),
      instanceType = httpClient.get(metadataUrl + "instance-type").body,
      availabilityZone = httpClient.get(metadataUrl + "placement/availability-zone").body,
      securityGroups = httpClient.get(metadataUrl + "security-groups").body,
      amiId = httpClient.get(metadataUrl + "ami-id").body,
      amiLaunchIndex = httpClient.get(metadataUrl + "ami-launch-index").body
    )
  }
}
