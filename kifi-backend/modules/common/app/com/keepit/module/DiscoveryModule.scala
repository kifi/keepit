package com.keepit.module

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Singleton, Provides, Provider}

import com.keepit.common.zookeeper
import com.keepit.common.logging.Logging
import com.keepit.common.service._
import com.keepit.common.amazon._
import com.keepit.common.net.HttpClient
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.zookeeper._

import play.api.Mode
import play.api.Mode._
import play.api.Play
import play.api.Play.current

class DiscoveryModule extends ScalaModule with Logging {

  def configure() { }

  @Singleton
  @Provides
  def serviceDiscovery(services: FortyTwoServices, mode: Mode, amazonInstanceInfoProvider: Provider[AmazonInstanceInfo]): ServiceDiscovery = mode match {
    case Mode.Prod =>
      //todo: have a dedicated host for zk (instead of using localhost)
      val servers = current.configuration.getString("zookeeper.servers").get
      val zk = new ZooKeeperClientImpl(servers, 2000,
        Some({zk1 => println(s"in callback, got $zk1")}))
      new ServiceDiscoveryImpl(zk, services, amazonInstanceInfoProvider)
    case _ =>
      new ServiceDiscovery {
        def serviceCluster(serviceType: ServiceType): ServiceCluster = new ServiceCluster(serviceType)
        def register() = Node("me")
        def isLeader() = true
      }
  }

  @Singleton
  @Provides
  def amazonInstanceInfo(httpClient: HttpClient): AmazonInstanceInfo = {
    val metadataUrl: String = "http://169.254.169.254/latest/meta-data/"

    val instance = AmazonInstanceInfo(
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
    log.info(s"my amazon instance is ${instance.toString}")
    instance
  }

}
