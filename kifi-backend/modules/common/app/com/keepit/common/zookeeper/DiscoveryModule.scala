package com.keepit.common.zookeeper

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Singleton, Provides, Provider}

import akka.actor.Scheduler

import com.keepit.common.logging.Logging
import com.keepit.common.service._
import com.keepit.common.amazon._
import com.keepit.common.net.{HttpClient,DirectUrl}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.KestrelCombinator
import com.keepit.common.amazon.AmazonInstanceId

import play.api.Play.current

import scala.Some
import scala.concurrent.{Future, Promise}

trait DiscoveryModule extends ScalaModule

case class ProdDiscoveryModule() extends DiscoveryModule with Logging {

  def configure() { }

  @Singleton
  @Provides
  def amazonInstanceInfo(httpClient: HttpClient): AmazonInstanceInfo = {
    val metadataUrl: String = "http://169.254.169.254/latest/meta-data/"

    val instance = AmazonInstanceInfo(
      instanceId = AmazonInstanceId(httpClient.get(DirectUrl(metadataUrl + "instance-id")).body),
      localHostname = httpClient.get(DirectUrl(metadataUrl + "local-hostname")).body,
      publicHostname = httpClient.get(DirectUrl(metadataUrl + "public-hostname")).body,
      localIp = IpAddress(httpClient.get(DirectUrl(metadataUrl + "local-ipv4")).body),
      publicIp = IpAddress(httpClient.get(DirectUrl(metadataUrl + "public-ipv4")).body),
      instanceType = httpClient.get(DirectUrl(metadataUrl + "instance-type")).body,
      availabilityZone = httpClient.get(DirectUrl(metadataUrl + "placement/availability-zone")).body,
      securityGroups = httpClient.get(DirectUrl(metadataUrl + "security-groups")).body,
      amiId = httpClient.get(DirectUrl(metadataUrl + "ami-id")).body,
      amiLaunchIndex = httpClient.get(DirectUrl(metadataUrl + "ami-launch-index")).body
    )
    log.info(s"my amazon instance is ${instance.toString}")
    instance
  }

  @Singleton
  @Provides
  def zooKeeperClient(): ZooKeeperClient = {
    val servers = current.configuration.getString("zookeeper.servers").get
    new ZooKeeperClientImpl(servers, 2000, Some({zk1 => println(s"in callback, got $zk1")}))
  }

  @Singleton
  @Provides
  def serviceDiscovery(zk: ZooKeeperClient, services: FortyTwoServices, amazonInstanceInfoProvider: Provider[AmazonInstanceInfo], scheduler: Scheduler): ServiceDiscovery = {
      new ServiceDiscoveryImpl(zk, services, amazonInstanceInfoProvider, scheduler)
  }

  @Singleton
  @Provides
  def configStore(zk: ZooKeeperClient): ConfigStore = {
    new ZkConfigStore(zk)
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
    new ServiceCluster(serviceType) tap {
      _.register(ServiceInstance(Node(s"${serviceType.name}_0"),
        RemoteService(amazonInstanceInfo, ServiceStatus.UP, serviceType), true))
    }

  @Singleton
  @Provides
  def serviceDiscovery(services: FortyTwoServices, amazonInstanceInfoProvider: Provider[AmazonInstanceInfo], cluster: ServiceCluster): ServiceDiscovery =
    new ServiceDiscovery {
      def thisInstance = Some(ServiceInstance(Node(cluster.serviceType.name + "_0"), RemoteService(amazonInstanceInfoProvider.get, ServiceStatus.UP, cluster.serviceType), true))
      def serviceCluster(serviceType: ServiceType): ServiceCluster = cluster
      def register(doKeepAlive: Boolean) = thisInstance.get
      def isLeader() = true
      def changeStatus(newStatus: ServiceStatus): Unit = {}
      def startSelfCheck(): Future[Boolean] = Promise[Boolean].success(true).future
      def forceUpdate(): Unit = {}
      def myStatus: Option[ServiceStatus] = Some(ServiceStatus.UP)
      def myVersion: ServiceVersion = services.currentVersion
    }

  @Singleton
  @Provides
  def configStore(): ConfigStore = {
    new InMemoryConfigStore()
  }

}

case class DevDiscoveryModule() extends LocalDiscoveryModule(ServiceType.DEV_MODE)
