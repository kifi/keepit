package com.keepit.common.zookeeper

import net.codingwell.scalaguice.{ ScalaMultibinder, ScalaModule }

import com.google.inject.{ Singleton, Provides, Provider }

import akka.actor.Scheduler

import com.keepit.common.logging.Logging
import com.keepit.common.service._
import com.keepit.common.amazon._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.core._
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.Play.current

import scala.concurrent.{ ExecutionContext, Await, Future, Promise }
import scala.collection.JavaConversions._
import play.api.libs.ws.WS
import scala.concurrent.duration._
import com.keepit.common.actor.{ DevActorSystemModule, ProdActorSystemModule }
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.{ AmazonEC2, AmazonEC2Client }
import com.amazonaws.auth.BasicAWSCredentials

abstract class ServiceTypeModule extends ScalaModule {
  val serviceType: ServiceType
  val servicesToListenOn: Seq[ServiceType]
  def configure() {
    bind[ServiceType].toInstance(serviceType)
    val servicesToListenOnBinder = ScalaMultibinder.newSetBinder[ServiceType](binder)
    servicesToListenOnBinder.permitDuplicates()

    servicesToListenOn.foreach { nextServiceToListenOn =>
      if (nextServiceToListenOn == serviceType) throw new IllegalArgumentException(s"Current service is included in servicesToListenOn: $servicesToListenOn")
      servicesToListenOnBinder.addBinding.toInstance(nextServiceToListenOn)
    }
  }
}

trait DiscoveryModule extends ScalaModule

object DiscoveryModule {

  lazy val isCanary = current.configuration.getBoolean("service.canary").getOrElse(false) // for "canary/sandbox" instance
  lazy val isLocal = current.configuration.getBoolean("service.local").getOrElse(false) // for "local-prod" testing -- can be removed when things settle down

  val LOCAL_AMZN_INFO = AmazonInstanceInfo(AmazonInstanceId("i-f168c1a8"),
    localHostname = "localhost",
    name = Some("dev"),
    service = None,
    publicHostname = "localhost",
    localIp = IpAddress("127.0.0.1"),
    publicIp = IpAddress("127.0.0.1"),
    instanceType = "c1.medium",
    availabilityZone = "us-west-1b",
    securityGroups = "default",
    amiId = "ami-1bf9de5e",
    amiLaunchIndex = "0",
    loadBalancer = None,
    tags = Map("Capabilities" -> "foo, bar", "ShardSpec" -> "0/1"))
}

case class UsedToBeProdDiscoveryModule() extends DiscoveryModule with Logging {

  def configure() {
    install(ProdActorSystemModule())
  }

  @Singleton
  @Provides
  def amazonEC2Client(basicAWSCredentials: BasicAWSCredentials): AmazonEC2 = {
    val conf = current.configuration.getConfig("amazon").get
    val ec2Client = new AmazonEC2Client(basicAWSCredentials)
    conf.getString("ec2.endpoint") foreach ec2Client.setEndpoint
    ec2Client
  }

  @Singleton
  @Provides
  def amazonInstanceInfo(amazonEC2Client: AmazonEC2): AmazonInstanceInfo = {

    val instance = if (DiscoveryModule.isCanary && DiscoveryModule.isLocal) {
      DiscoveryModule.LOCAL_AMZN_INFO
    } else {
      def get(path: String): String = {
        Await.result(WS.url("http://169.254.169.254/latest/meta-data/" + path).get(), 1 minute).body
      }
      val instanceId: String = get("instance-id")
      val request = new DescribeInstancesRequest()
      request.setInstanceIds(Seq(instanceId))
      val result = amazonEC2Client.describeInstances(request)
      val tags = for {
        reservation <- result.getReservations.headOption.toSeq
        instance <- reservation.getInstances.headOption.toSeq
        tag <- instance.getTags
      } yield tag
      val name = tags.find(_.getKey == "Name") map (_.getValue)
      val service = tags.find(_.getKey == "Service") map (_.getValue)
      val loadBalancer = tags.find(_.getKey == "ELB") map (_.getValue)

      AmazonInstanceInfo(
        instanceId = AmazonInstanceId(instanceId),
        name = name,
        service = service,
        localHostname = get("local-hostname"),
        publicHostname = get("public-hostname"),
        localIp = IpAddress(get("local-ipv4")),
        publicIp = IpAddress(get("public-ipv4")),
        instanceType = get("instance-type"),
        availabilityZone = get("placement/availability-zone"),
        securityGroups = get("security-groups"),
        amiId = get("ami-id"),
        amiLaunchIndex = get("ami-launch-index"),
        loadBalancer = loadBalancer,
        tags = tags.map(tag => tag.getKey -> tag.getValue).toMap
      )
    }
    log.info(s"my amazon instance is ${instance.toString}")
    instance
  }

  @Singleton
  @Provides
  def myAmazonInstanceInfo(info: AmazonInstanceInfo, serviceType: ServiceType, airbrake: AirbrakeNotifier): MyInstanceInfo = {
    val myInstanceInfo = MyInstanceInfo(info, serviceType)
    if (myInstanceInfo.info.instantTypeInfo == AmazonInstanceType.UNKNOWN) {
      airbrake.notify(s"Unknown machine type for current instance: $myInstanceInfo")
    }
    myInstanceInfo
  }

  @Singleton
  @Provides
  def zooKeeperClient(): ZooKeeperClient = {
    val servers = current.configuration.getString("zookeeper.servers").get
    new ZooKeeperClientImpl(servers, 2000, Some({ zk1 => println(s"in callback, got $zk1") }))
  }

  @Singleton
  @Provides
  def serviceDiscovery(serviceType: ServiceType, servicesToListenOn: Set[ServiceType],
    zk: ZooKeeperClient, airbrake: Provider[AirbrakeNotifier], services: FortyTwoServices,
    amazonInstanceInfo: AmazonInstanceInfo, scheduler: Scheduler, executionContext: ExecutionContext): ServiceDiscovery = {
    val isCanary = DiscoveryModule.isCanary
    if (serviceType == ServiceType.SHOEBOX && isCanary) {
      new ServiceDiscoveryImpl(zk, services, amazonInstanceInfo, scheduler, airbrake, isCanary = isCanary, servicesToListenOn = servicesToListenOn + ServiceType.SHOEBOX, executionContext)
    } else {
      new ServiceDiscoveryImpl(zk, services, amazonInstanceInfo, scheduler, airbrake, isCanary = isCanary, servicesToListenOn = servicesToListenOn, executionContext)
    }
  }

  @Singleton
  @Provides
  def configStore(zk: ZooKeeperClient): ConfigStore = {
    new ZkConfigStore(zk)
  }

}

class ProdDiscoveryModule(serviceType: ServiceType = ServiceType.SHOEBOX) extends DiscoveryModule {

  def configure() {
    install(ProdActorSystemModule())
  }

  @Singleton
  @Provides
  def myAmazonInstanceInfo(info: AmazonInstanceInfo): MyInstanceInfo = MyInstanceInfo(info, serviceType)

  @Singleton
  @Provides
  def amazonInstanceInfo: AmazonInstanceInfo = DiscoveryModule.LOCAL_AMZN_INFO

  @Provides
  @Singleton
  def serviceCluster(amazonInstanceInfo: AmazonInstanceInfo, airbrake: Provider[AirbrakeNotifier], scheduler: Scheduler): ServiceCluster =
    new ServiceCluster(serviceType, airbrake, scheduler, () => {}) tap { cluster =>
      cluster.register(new ServiceInstance(Node(cluster.servicePath, cluster.serviceType.name + "_0"), true, RemoteService(amazonInstanceInfo, ServiceStatus.UP, serviceType)))
    }

  @Singleton
  @Provides
  def serviceDiscovery(actualServiceType: ServiceType, serviceType: ServiceType, servicesToListenOn: Set[ServiceType], services: FortyTwoServices, amazonInstanceInfo: AmazonInstanceInfo, cluster: ServiceCluster): ServiceDiscovery =
    new ServiceDiscovery {
      val availableServices = servicesToListenOn + actualServiceType
      var state: Option[ServiceStatus] = Some(ServiceStatus.UP)
      def timeSinceLastStatusChange: Long = 0L
      def thisInstance = Some(new ServiceInstance(Node(cluster.servicePath, cluster.serviceType.name + "_0"), true, RemoteService(amazonInstanceInfo, ServiceStatus.UP, cluster.serviceType)))
      def thisService: ServiceType = cluster.serviceType
      def serviceCluster(serviceType: ServiceType): ServiceCluster = if (availableServices.contains(serviceType)) cluster else throw new UnknownServiceException(s"DiscoveryService is not listening to service $serviceType.")
      def register() = thisInstance.get
      def isLeader() = true
      def instancesInCluster: Seq[ServiceInstance] = Seq(thisInstance.get)
      def isRunnerFor(taskName: String) = true
      def changeStatus(newStatus: ServiceStatus): Unit = { state = Some(newStatus) }
      def startSelfCheck(): Future[Boolean] = Promise[Boolean].success(true).future
      def forceUpdate(): Unit = {}
      def myStatus: Option[ServiceStatus] = state
      def myVersion: ServiceVersion = services.currentVersion
      def hasBackupCapability: Boolean = false
      def hasOfflineCapability: Boolean = false
      def isCanary = false
    }

  @Singleton
  @Provides
  def configStore(): ConfigStore = {
    new InMemoryConfigStore()
  }

  @Provides @Singleton
  def fakeZooKeeperClient: ZooKeeperClient = new FakeZooKeeperClient()
}

case class DevDiscoveryModule() extends ProdDiscoveryModule(ServiceType.DEV_MODE) {
  override def configure() {
    install(DevActorSystemModule())
  }
}
