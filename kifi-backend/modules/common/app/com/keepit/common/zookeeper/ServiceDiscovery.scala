package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

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
  def register(doKeepAlive: Boolean = true): ServiceInstance
  def unRegister(): Unit = {}
  def isLeader(): Boolean
  def myClusterSize: Int = 0
  def startSelfCheck(): Future[Boolean]
  def changeStatus(newStatus: ServiceStatus): Unit
  def forceUpdate(): Unit
  def myStatus: Option[ServiceStatus]
  def myVersion: ServiceVersion
  def thisInstance: Option[ServiceInstance]
}

@Singleton
class ServiceDiscoveryImpl @Inject() (
    zk: ZooKeeperClient,
    services: FortyTwoServices,
    amazonInstanceInfoProvider: Provider[AmazonInstanceInfo],
    scheduler: Scheduler,
    servicesToListenOn: Seq[ServiceType] =
        ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.SCRAPER :: Nil)
  extends ServiceDiscovery with Logging {

  private var myInstance: Option[ServiceInstance] = None
  private var selfCheckIsRunning: Boolean = false
  private var selfCheckFutureOpt: Option[Future[Boolean]] = None

  def thisInstance: Option[ServiceInstance] = myInstance

  private val clusters: TrieMap[ServiceType, ServiceCluster] = {
    val clustersToInit = new TrieMap[ServiceType, ServiceCluster]()
    servicesToListenOn foreach {service =>
      val cluster = new ServiceCluster(service)
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
      forceUpdate()
      if (stillRegistered) {
        keepAlive()
      } else {
        log.warn("Zookeeper seems to have lost me! Re-registering.")
        register()
        changeStatus(ServiceStatus.UP)
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

  def register(doKeepAlive: Boolean = true): ServiceInstance = {
    val myServiceType: ServiceType = services.currentService
    log.info(s"registered clusters: $clusters, my service is $myServiceType")
    val instanceInfo = amazonInstanceInfoProvider.get
    val thisRemoteService = RemoteService(instanceInfo, ServiceStatus.STARTING, myServiceType)
    val myNode = zk.createNode(myCluster.serviceNodeMaster, RemoteService.toJson(thisRemoteService), EPHEMERAL_SEQUENTIAL)
    myInstance = Some(myCluster.register(ServiceInstance(myNode, thisRemoteService, true)))
    log.info(s"registered as ${myInstance.get}")
    watchServices()
    if (doKeepAlive) keepAlive()
    myInstance.get
  }

  override def unRegister(): Unit = myInstance map {instance => zk.deleteNode(instance.node)}

  def changeStatus(newStatus: ServiceStatus) : Unit = if(stillRegistered()) {
    myInstance.map { instance =>
      log.info(s"Changing instance status to $newStatus")
      instance.remoteService.status = newStatus
      zk.set(instance.node, RemoteService.toJson(instance.remoteService))
    }
  }

  def myStatus : Option[ServiceStatus] = myInstance.map(_.remoteService.status)
  private def myHealthyStatus: Option[ServiceStatus] = myInstance.map(_.remoteService.healthyStatus)

  def myVersion: ServiceVersion = services.currentVersion

  def startSelfCheck(): Future[Boolean] = synchronized {
    if (!selfCheckIsRunning) {
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

  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val serviceStatusFormat = ServiceStatus.format
  implicit val ipAddressFormat = Json.format[IpAddress]
  implicit val serviceTypeFormat = ServiceType.format

}

