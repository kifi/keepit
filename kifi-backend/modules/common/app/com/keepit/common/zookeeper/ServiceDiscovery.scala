package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._
import com.keepit.common.healthcheck.AirbrakeNotifier

import scala.concurrent._
import scala.collection.concurrent.TrieMap
import scala.util.{ Success, Failure }

import akka.actor.Scheduler

import play.api.libs.json._

import com.google.inject.Provider

import org.apache.zookeeper.CreateMode._

trait ServiceDiscovery {
  def serviceCluster(serviceType: ServiceType): ServiceCluster
  def register(): ServiceInstance
  def unRegister(): Unit = {}
  def isLeader(): Boolean
  def myClusterSize: Int = 0
  def startSelfCheck(): Future[Boolean]
  def changeStatus(newStatus: ServiceStatus): Unit
  def forceUpdate(): Unit
  def myStatus: Option[ServiceStatus]
  def myVersion: ServiceVersion
  def thisInstance: Option[ServiceInstance]
  def instancesInCluster: Seq[ServiceInstance]
  def thisService: ServiceType
  def timeSinceLastStatusChange: Long
  def myHealthyStatus: Option[ServiceStatus] = thisInstance.map(_.remoteService.healthyStatus)
  def amIUp: Boolean = myStatus.exists { status =>
    myHealthyStatus.exists(_ == status)
  }
  def hasBackupCapability: Boolean
  def isCanary: Boolean
}

class UnknownServiceException(message: String) extends Exception(message)

class ServiceDiscoveryImpl(
  zkClient: ZooKeeperClient,
  services: FortyTwoServices,
  amazonInstanceInfo: AmazonInstanceInfo,
  scheduler: Scheduler,
  airbrake: Provider[AirbrakeNotifier],
  val isCanary: Boolean = false,
  servicesToListenOn: Set[ServiceType],
  implicit val executionContext: ExecutionContext)
    extends ServiceDiscovery with Logging {

  @volatile private[this] var lastStatusChangeTime = System.currentTimeMillis

  private[this] val registrationLock = new AnyRef
  @volatile private[this] var registered = false
  @volatile private[this] var unregistered = false

  private lazy val myAmazonInstanceInfo = amazonInstanceInfo
  @volatile private[this] var myInstance: Option[ServiceInstance] = None
  @volatile private[this] var myServiceStatus: ServiceStatus = ServiceStatus.STARTING // keeping track of the status

  @volatile private[this] var selfCheckIsRunning: Boolean = false
  @volatile private[this] var selfCheckFutureOpt: Option[Future[Boolean]] = None

  private def getThisRemoteService = RemoteService(myAmazonInstanceInfo, myServiceStatus, services.currentService)

  def thisInstance: Option[ServiceInstance] = myInstance
  def thisService: ServiceType = services.currentService

  private[this] val clusters: TrieMap[ServiceType, ServiceCluster] = {
    val clustersToInit = new TrieMap[ServiceType, ServiceCluster]()
    val myCluster = new ServiceCluster(services.currentService, airbrake, scheduler, () => { forceUpdate() })
    clustersToInit(services.currentService) = myCluster
    if (servicesToListenOn.contains(services.currentService)) throw new IllegalArgumentException(s"current service is included in servicesToListenOn: $servicesToListenOn")
    servicesToListenOn foreach { service =>
      val cluster = new ServiceCluster(service, airbrake, scheduler, () => { forceUpdate() })
      clustersToInit(service) = cluster
    }
    log.info(s"registered clusters: $clustersToInit")
    clustersToInit
  }

  private[this] val myCluster = clusters(services.currentService)

  def serviceCluster(serviceType: ServiceType): ServiceCluster =
    clusters.getOrElse(serviceType, throw new UnknownServiceException(s"DiscoveryService is not listening to service $serviceType."))

  /**
   * We don't want to be too chatty on the logs, it may grow very fast since the method is very hot
   */
  private[this] var lastLeaderLogTime = 0L

  def instancesInCluster: Seq[ServiceInstance] = myCluster.allMembers.toSeq

  def isLeader(): Boolean = if (isCanary) false else zkClient.session { zk =>
    if (!stillRegistered()) {
      log.warn(s"service did not register itself yet!")
      return false
    }
    val now = System.currentTimeMillis()
    val logMe = (now - lastLeaderLogTime) > 30000 //30 sec
    def logLeader(msg: => String): Unit = {
      //best effort, race conditions could happen but we don't want to lock
      log.info(msg)
      lastLeaderLogTime = now
    }

    myCluster.leader match {
      case Some(instance) if instance == myInstance.get =>
        require(myCluster.size > 0)
        if (logMe) {
          logLeader(s"I'm the leader! ${myInstance.get}")
          statsd.gauge(s"service.leader.${myCluster.serviceType.shortName}", 1)
        }
        return true
      case Some(instance) =>
        require(myCluster.size > 1)
        if (logMe) logLeader(s"I'm not the leader since my instance is ${myInstance.get} and the leader is $instance")
        return false
      case None =>
        if (logMe) logLeader(s"I'm not the leader since my instance is ${myInstance.get} and I have no idea who the leader is")
        require(myCluster.size == 0)
        return false
    }
  }

  override def toString(): String = clusters.map(kv => kv._1.toString + ":" + kv._2.toString).mkString("\n")

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  override def myClusterSize: Int = myCluster.size

  private def stillRegistered(): Boolean = myInstance.exists { instance =>
    myCluster.instanceForNode(instance.node).isDefined
  }

  private def watchServices(zk: ZooKeeperSession): Unit = clusters.values.foreach { cluster => watchService(zk, cluster) }

  private def watchService(zk: ZooKeeperSession, cluster: ServiceCluster): Unit = {
    zk.create(cluster.servicePath)
    zk.watchChildrenWithData[String](cluster.servicePath, { children: Seq[(Node, String)] =>
      log.info(s"""services in my cluster under ${cluster.servicePath.name}: ${children.mkString(", ")}""")
      cluster.update(zk, children)
    })
  }

  @volatile private[this] var forceUpdateInProgress = false
  def forceUpdate(): Unit = if (!forceUpdateInProgress) synchronized {
    if (!forceUpdateInProgress) {
      forceUpdateInProgress = true
      try {
        zkClient.session { zk =>
          for (cluster <- clusters.values) {
            val children = zk.getChildren(cluster.servicePath).map(child => (child, zk.getData[String](child).get))
            cluster.update(zk, children)
          }
        }
      } finally {
        forceUpdateInProgress = false
      }
    }
  }

  def register(): ServiceInstance = registrationLock.synchronized {
    if (unregistered) throw new IllegalStateException("unable to register once unregistered")

    registered = true
    zkClient.onConnected { zk => doRegister(zk) } // It is expected that zk is ready at this point and invokes doRegister immediately
    myInstance.get
  }

  private def doRegister(zk: ZooKeeperSession): Unit = {
    val thisRemoteService = getThisRemoteService
    if (registered) {
      log.info(s"registered clusters: $clusters, my service is ${thisRemoteService.serviceType}, my instance is $myInstance")

      //if the instance already exist, unregister it
      myInstance foreach { instance =>
        try {
          log.warn(s"deleting instance $instance from zookeeper before re-registering itself")
          zk.delete(instance.node)
        } catch {
          case e: Throwable =>
            log.info("trying to delete node on re-registration, safe to ignore", e)
        } finally {
          myInstance = None
        }
      }

      val myNode = zk.createChild(myCluster.servicePath, myCluster.serviceType.name + "_", RemoteService.toJson(thisRemoteService), EPHEMERAL_SEQUENTIAL)
      myInstance = Some(new ServiceInstance(myNode, true, thisRemoteService))
      myCluster.register(myInstance.get)
      log.info(s"registered as ${myInstance.get}")
      watchServices(zk)
    }
  }

  override def unRegister(): Unit = registrationLock.synchronized {
    registered = false
    unregistered = true
    myInstance foreach { instance => zkClient.session { zk => zk.delete(instance.node) } }
    myInstance = None
  }

  def changeStatus(newStatus: ServiceStatus): Unit = zkClient.session { zk =>
    if (stillRegistered()) synchronized {
      myInstance foreach { instance =>
        log.info(s"Changing instance status to $newStatus")
        lastStatusChangeTime = System.currentTimeMillis
        myServiceStatus = newStatus
        myInstance = Some(new ServiceInstance(instance.node, true, getThisRemoteService))
        zk.setData(myInstance.get.node, RemoteService.toJson(myInstance.get.remoteService))
      }
    }
  }

  def myStatus: Option[ServiceStatus] = myInstance.map(_.remoteService.status)

  def myVersion: ServiceVersion = services.currentVersion

  def startSelfCheck(): Future[Boolean] = synchronized {
    if (!selfCheckIsRunning && (myStatus.isEmpty || myStatus.get != ServiceStatus.STOPPING)) {
      selfCheckIsRunning = true
      log.info("Running self check")
      val selfCheckPromise = Promise[Boolean]
      val selfCheckFuture = services.currentService.selfCheck()
      selfCheckFuture.onComplete {
        case Success(passed) =>
          if (passed) {
            changeStatus(myHealthyStatus.get)
            selfCheckPromise.success(true)
          } else {
            changeStatus(ServiceStatus.SELFCHECK_FAIL)
            selfCheckPromise.success(false)
          }
          selfCheckIsRunning = false
        case Failure(e) =>
          changeStatus(ServiceStatus.SELFCHECK_FAIL)
          selfCheckIsRunning = false
          selfCheckPromise.success(false)
      }
      selfCheckFutureOpt = Some(selfCheckPromise.future)
    }
    selfCheckFutureOpt.get //this option must be defined when we are in this case
  }

  def timeSinceLastStatusChange: Long = System.currentTimeMillis - lastStatusChangeTime

  def hasBackupCapability: Boolean = amazonInstanceInfo.tags.get("Capabilities").map { _.trim } == Some("backup")
  def hasOfflineCapability: Boolean = amazonInstanceInfo.tags.get("Capabilities").map { _.trim } == Some("offline")

  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val serviceStatusFormat = ServiceStatus.format
  implicit val ipAddressFormat = Json.format[IpAddress]
  implicit val serviceTypeFormat = ServiceType.format

}

