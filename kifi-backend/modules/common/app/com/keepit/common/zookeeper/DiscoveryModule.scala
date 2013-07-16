package com.keepit.common.zookeeper

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Singleton, Provides, Provider}

import akka.actor.Scheduler

import com.keepit.common.logging.Logging
import com.keepit.common.service._
import com.keepit.common.amazon._
import com.keepit.common.net.HttpClient
import com.keepit.common.service.FortyTwoServices
import play.api.Play.current
import scala.Some
import com.keepit.common.amazon.AmazonInstanceId

trait DiscoveryModule extends ScalaModule

case class ProdDiscoveryModule() extends DiscoveryModule with Logging {

  def configure() { }

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

  @Singleton
  @Provides
  def serviceDiscovery(services: FortyTwoServices, amazonInstanceInfoProvider: Provider[AmazonInstanceInfo], scheduler: Scheduler): ServiceDiscovery = {
      //todo: have a dedicated host for zk (instead of using localhost)
      val servers = current.configuration.getString("zookeeper.servers").get
      val zk = new ZooKeeperClientImpl(servers, 2000,
        Some({zk1 => println(s"in callback, got $zk1")}))
      new ServiceDiscoveryImpl(zk, services, amazonInstanceInfoProvider, scheduler)
  }
}

abstract class LocalDiscoveryModule(serviceType: ServiceType) extends DiscoveryModule {

  def configure() {}

  @Singleton
  @Provides
  def amazonInstanceInfo: AmazonInstanceInfo =
    new AmazonInstanceInfo(
      instanceId = AmazonInstanceId("i-f168c1a8"),
      localHostname = "localhost",
      publicHostname = "localhost",
      localIp = IpAddress("127.0.0.1"),
      publicIp = IpAddress("127.0.0.1"),
      instanceType = "c1.medium",
      availabilityZone = "us-west-1b",
      securityGroups = "default",
      amiId = "ami-1bf9de5e",
      amiLaunchIndex = "0"
    )

  @Provides
  @Singleton
  def serviceCluster(amazonInstanceInfo: AmazonInstanceInfo): ServiceCluster =
    new ServiceCluster(serviceType).register(Node(serviceType.name + "_0"), RemoteService(amazonInstanceInfo, ServiceStatus.UP, serviceType))

  @Singleton
  @Provides
  def serviceDiscovery(services: FortyTwoServices, amazonInstanceInfoProvider: Provider[AmazonInstanceInfo], cluster: ServiceCluster): ServiceDiscovery =
    new ServiceDiscovery {
      def serviceCluster(serviceType: ServiceType): ServiceCluster = cluster
      def register() = Node("me")
      def isLeader() = true
      def changeStatus(newStatus: ServiceStatus): Unit = {}
      def startSelfCheck(): Unit = {}
      def forceUpdate(): Unit = {}
      def myStatus: Option[ServiceStatus] = Some(ServiceStatus.UP)
      def myVersion: ServiceVersion = services.currentVersion
    }
}

case class DevDiscoveryModule() extends LocalDiscoveryModule(ServiceType.DEV_MODE)
