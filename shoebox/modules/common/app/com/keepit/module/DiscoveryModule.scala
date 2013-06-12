package com.keepit.module

import com.keepit.common.zookeeper._
import com.keepit.common.logging.Logging
import com.keepit.common.service._
import com.keepit.common.amazon._

import com.keepit.common.net.HttpClient

import play.api.Play
import play.api.Play.current
import play.api.Mode
import play.api.Mode._

import com.tzavellas.sse.guice.ScalaModule

import com.google.inject.{Provider, Inject, Singleton, Provides}

class DiscoveryModule extends ScalaModule with Logging {

  def configure() { }

  @Singleton
  @Provides
  def serviceDiscovery(services: FortyTwoServices, mode: Mode, amazonInstanceInfo: AmazonInstanceInfo): ServiceDiscovery = mode match {
    case Mode.Prod =>
      //todo: have a dedicated host for zk (instead of using localhost)
      val zk = new ZooKeeperClientImpl("localhost", 2000, Path("/fortytwo"),
        Some({zk1 => println(s"in callback, got $zk1")}))
      new ServiceDiscoveryImpl(zk, services, amazonInstanceInfo)
    case _ =>
      new ServiceDiscovery {
        def register() = Node("me")
        def isLeader() = true
      }
  }

  @Singleton
  @Provides
  def amazonInstanceInfo(httpClient: HttpClient): AmazonInstanceInfo = {
    val metadataUrl: String = "http://169.254.169.254/latest/meta-data/"

    AmazonInstanceInfo(
      instanceId = AmazonInstanceId(httpClient.get(metadataUrl + "instance-id").body),
      localHostname = httpClient.get(metadataUrl + "local-hostname").body,
      publicHostname = httpClient.get(metadataUrl + "public-hostname").body,
      localIp = IpAddress(httpClient.get(metadataUrl + "local-ipv4").body),
      publicIp = IpAddress(httpClient.get(metadataUrl + "public-ipv4").body),
      instanceType = httpClient.get(metadataUrl + "instance-type").body,
      availabilityZone = httpClient.get(metadataUrl + "placement/availability-zone").body,
      securityGroups = httpClient.get(metadataUrl + "security-groups").body,
      amiId = httpClient.get(metadataUrl + "ami-id").body,
      amiLaunchIndex = httpClient.get(metadataUrl + "ami-launch-index").body
    )
  }

}
