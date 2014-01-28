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
import com.keepit.common.healthcheck.AirbrakeNotifier


import play.api.Play.current

import scala.Some
import scala.concurrent.{Await, Future, Promise}
import play.api.libs.ws.WS
import scala.concurrent.duration._

trait DiscoveryModule extends ScalaModule

object DiscoveryModule {

  val isCanary = sys.props.getOrElse("service.canary", "false").toBoolean // for "canary/sandbox" instance
  val isLocal  = sys.props.getOrElse("service.local",  "false").toBoolean // for "local-prod" testing -- can be removed when things settle down

  val LOCAL_AMZN_INFO = AmazonInstanceInfo(AmazonInstanceId("i-f168c1a8"),
    localHostname = "localhost",
    publicHostname = "localhost",
    localIp = IpAddress("127.0.0.1"),
    publicIp = IpAddress("127.0.0.1"),
    instanceType = "c1.medium",
    availabilityZone = "us-west-1b",
    securityGroups = "default",
    amiId = "ami-1bf9de5e",
    amiLaunchIndex = "0")

}

trait ProdDiscoveryModule extends DiscoveryModule with Logging {

  def configure() { }

  @Singleton
  @Provides
  def amazonInstanceInfo(): AmazonInstanceInfo = {

    val instance = if (DiscoveryModule.isCanary && DiscoveryModule.isLocal) {
      DiscoveryModule.LOCAL_AMZN_INFO
    } else {
      def get(path: String): String = {
        Await.result(WS.url("http://169.254.169.254/latest/meta-data/" + path).get(), 1 minute).body
      }
      AmazonInstanceInfo(
        instanceId = AmazonInstanceId(get("instance-id")),
        localHostname = get("local-hostname"),
        publicHostname = get("public-hostname"),
        localIp = IpAddress(get("local-ipv4")),
        publicIp = IpAddress(get("public-ipv4")),
        instanceType = get("instance-type"),
        availabilityZone = get("placement/availability-zone"),
        securityGroups = get("security-groups"),
        amiId = get("ami-id"),
        amiLaunchIndex = get("ami-launch-index")
      )
    }
    log.info(s"my amazon instance is ${instance.toString}")
    instance
  }

  @Singleton
  @Provides
  def myAmazonInstanceInfo(info: AmazonInstanceInfo): MyAmazonInstanceInfo = MyAmazonInstanceInfo(info)

  @Singleton
  @Provides
  def zooKeeperClient(): ZooKeeperClient = {
    val servers = current.configuration.getString("zookeeper.servers").get
    new ZooKeeperClientImpl(servers, 2000, Some({zk1 => println(s"in callback, got $zk1")}))
  }

  @Singleton
  @Provides
  def serviceDiscovery(zk: ZooKeeperClient, airbrake: Provider[AirbrakeNotifier], services: FortyTwoServices, amazonInstanceInfoProvider: Provider[AmazonInstanceInfo], scheduler: Scheduler): ServiceDiscovery = {
    new ServiceDiscoveryImpl(zk, services, amazonInstanceInfoProvider, scheduler, airbrake, isCanary = DiscoveryModule.isCanary, servicesToListenOn = servicesToListenOn)
  }

  def servicesToListenOn: Seq[ServiceType]

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
  def myAmazonInstanceInfo(info: AmazonInstanceInfo): MyAmazonInstanceInfo = MyAmazonInstanceInfo(info)

  @Singleton
  @Provides
  def amazonInstanceInfo: AmazonInstanceInfo = DiscoveryModule.LOCAL_AMZN_INFO

  @Provides
  @Singleton
  def serviceCluster(amazonInstanceInfo: AmazonInstanceInfo, airbrake: Provider[AirbrakeNotifier]): ServiceCluster =
    new ServiceCluster(serviceType, airbrake) tap {
      _.register(new ServiceInstance(Node(s"${serviceType.name}_0"), true).setRemoteService(RemoteService(amazonInstanceInfo, ServiceStatus.UP, serviceType)))
    }

  @Singleton
  @Provides
  def serviceDiscovery(services: FortyTwoServices, amazonInstanceInfoProvider: Provider[AmazonInstanceInfo], cluster: ServiceCluster): ServiceDiscovery =
    new ServiceDiscovery {
      def timeSinceLastStatusChange: Long = 0L
      def thisInstance = Some(new ServiceInstance(Node(cluster.serviceType.name + "_0"), true).setRemoteService(RemoteService(amazonInstanceInfoProvider.get, ServiceStatus.UP, cluster.serviceType)))
      def serviceCluster(serviceType: ServiceType): ServiceCluster = cluster
      def register() = thisInstance.get
      def isLeader() = true
      def changeStatus(newStatus: ServiceStatus): Unit = {}
      def startSelfCheck(): Future[Boolean] = Promise[Boolean].success(true).future
      def forceUpdate(): Unit = {}
      def myStatus: Option[ServiceStatus] = Some(ServiceStatus.UP)
      def myVersion: ServiceVersion = services.currentVersion
      def amIUp: Boolean = true
      def isCanary = false
    }

  @Singleton
  @Provides
  def configStore(): ConfigStore = {
    new InMemoryConfigStore()
  }

}

case class DevDiscoveryModule() extends LocalDiscoveryModule(ServiceType.DEV_MODE)
