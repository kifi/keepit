package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._
import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import scala.util.{Try, Success, Failure}
import scala.annotation.tailrec

import akka.actor.Scheduler

import play.api.libs.json._

import com.google.inject.{Inject, Singleton, Provider}

import org.apache.zookeeper.CreateMode._
import com.keepit.common.akka.SlowRunningExecutionContext

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
  def amIUp: Boolean
}

class ServiceDiscoveryImpl(
    zk: ZooKeeperClient,
    services: FortyTwoServices,
    amazonInstanceInfoProvider: Provider[AmazonInstanceInfo],
    scheduler: Scheduler,
    airbrake: Provider[AirbrakeNotifier],
    servicesToListenOn: Seq[ServiceType] =
        ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.SCRAPER :: Nil,
    doKeepAlive: Boolean = true)
  extends ServiceDiscovery with Logging {


  @volatile private[this] var registered = false
  @volatile private[this] var unregistered = false

  private val thisRemoteService = RemoteService(amazonInstanceInfoProvider.get, ServiceStatus.STARTING, services.currentService) // keeping track of the status
  private var myInstance: Option[ServiceInstance] = None
  private var selfCheckIsRunning: Boolean = false
  private var selfCheckFutureOpt: Option[Future[Boolean]] = None

  def thisInstance: Option[ServiceInstance] = myInstance

  private val clusters: TrieMap[ServiceType, ServiceCluster] = {
    val clustersToInit = new TrieMap[ServiceType, ServiceCluster]()
    servicesToListenOn foreach {service =>
      val cluster = new ServiceCluster(service, airbrake, if (services.currentService==ServiceType.SHOEBOX) Some(scheduler) else None)
      clustersToInit(service) = cluster
    }
    log.info(s"registered clusters: $clustersToInit")
    clustersToInit
  }

  private val myCluster = clusters(services.currentService)

  def serviceCluster(serviceType: ServiceType): ServiceCluster = clusters(serviceType)

  def isLeader: Boolean = {
    if (!stillRegistered()) {
      log.warn(s"service did not register itself yet!")
      return false
    }
    myCluster.leader match {
      case Some(instance) if instance == myInstance.get =>
        require(myCluster.size > 0)
        return true
      case Some(instance)  =>
        require(myCluster.size > 1)
        log.debug(s"I'm not the leader since my instance is ${myInstance.get} and the leader is $instance")
        return false
      case None =>
        require(myCluster.size == 0)
        return false
    }
  }

  override def toString(): String = clusters.map(kv => kv._1.toString + ":" + kv._2.toString).mkString("\n")

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  override def myClusterSize: Int = myCluster.size

  private def stillRegistered(): Boolean = myInstance forall {instance =>
    myCluster.instanceForNode(instance.node).isDefined
  }

  private def keepAlive() : Unit = {
    scheduler.scheduleOnce(2 minutes){
      if (registered) {
        forceUpdate()
        if (stillRegistered) {
          keepAlive()
        } else {
          log.warn("Zookeeper seems to have lost me! Re-registering.")
          doRegister()
        }
      }
    }
  }

  private def watchServices(): Unit = clusters.values foreach watchService

  private def watchService(cluster: ServiceCluster): Unit = {
    zk.createPath(cluster.servicePath)
    zk.watchChildren(cluster.servicePath, { (children : Seq[Node]) =>
      log.info(s"""services in my cluster under ${cluster.servicePath.name}: ${children.mkString(", ")}""")
      cluster.update(zk, children)
    })
  }

  def forceUpdate() : Unit = {
    for (cluster <- clusters.values) {
      val children = zk.getChildren(cluster.servicePath)
      cluster.update(zk, children)
    }
  }

  def register(): ServiceInstance = {
    if (unregistered) throw new IllegalStateException("unable to register once unregistered")

    registered = true
    zk.onConnected{ () => doRegister() } // It is expected that zk is ready at this point and invokes doRegister immediately
    if (doKeepAlive) keepAlive()
    myInstance.get
  }

  private def doRegister(): Unit = {
    if (registered) {
      log.info(s"registered clusters: $clusters, my service is ${thisRemoteService.serviceType}")
      val myNode = zk.createNode(myCluster.serviceNodeMaster, RemoteService.toJson(thisRemoteService), EPHEMERAL_SEQUENTIAL)
      myInstance = Some(ServiceInstance(myNode, thisRemoteService, true))
      myCluster.register(myInstance.get)
      log.info(s"registered as ${myInstance.get}")
      watchServices()
    }
  }

  override def unRegister(): Unit = {
    registered = false
    unregistered = true
    myInstance map {instance => zk.deleteNode(instance.node)}
    myInstance = None
  }

  def changeStatus(newStatus: ServiceStatus) : Unit = if(stillRegistered()) {
    myInstance.map { instance =>
      log.info(s"Changing instance status to $newStatus")
      thisRemoteService.status = newStatus
      instance.remoteService = thisRemoteService
      zk.set(instance.node, RemoteService.toJson(instance.remoteService))
    }
  }

  def myStatus : Option[ServiceStatus] = myInstance.map(_.remoteService.status)
  private def myHealthyStatus: Option[ServiceStatus] = myInstance.map(_.remoteService.healthyStatus)

  def myVersion: ServiceVersion = services.currentVersion

  def startSelfCheck(): Future[Boolean] = synchronized {
    if (!selfCheckIsRunning && (myStatus.isEmpty || myStatus.get != ServiceStatus.STOPPING)) {
      selfCheckIsRunning = true
      log.info("Running self check")
      val selfCheckPromise = Promise[Boolean]
      val selfCheckFuture = services.currentService.selfCheck()
      selfCheckFuture.onComplete{
          case Success(passed) =>
            val result = if (passed) {
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

  def amIUp: Boolean = {
    myStatus.map{ status =>
      myHealthyStatus.map(_==status).getOrElse(false)
    } getOrElse(false)
  }

  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val serviceStatusFormat = ServiceStatus.format
  implicit val ipAddressFormat = Json.format[IpAddress]
  implicit val serviceTypeFormat = ServiceType.format

}

