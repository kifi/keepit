package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import scala.util.{Try, Success, Failure}
import scala.annotation.tailrec

import akka.actor.Scheduler

import play.api.libs.json._

import com.google.inject.{Inject, Singleton, Provider}

import org.apache.zookeeper.CreateMode._

trait ServiceDiscovery {
  def serviceCluster(serviceType: ServiceType): ServiceCluster
  def register(): Node
  def unRegister(): Unit = {}
  def isLeader(): Boolean
  def myClusterSize: Int = 0
  def startSelfCheck(): Unit
  def changeStatus(newStatus: ServiceStatus): Unit
  def forceUpdate(): Unit
  def myStatus: Option[ServiceStatus]
  def myVersion: ServiceVersion
}

@Singleton
class ServiceDiscoveryImpl @Inject() (
    zk: ZooKeeperClient,
    services: FortyTwoServices,
    amazonInstanceInfoProvider: Provider[AmazonInstanceInfo],
    scheduler: Scheduler,
    servicesToListenOn: Seq[ServiceType] = ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.ELIZA :: Nil)
  extends ServiceDiscovery with Logging {

  private var myNode: Option[Node] = None

  private val clusters: TrieMap[ServiceType, ServiceCluster] = {
    val clustersToInit = new TrieMap[ServiceType, ServiceCluster]()
    servicesToListenOn foreach {service =>
      val cluster = new ServiceCluster(service)
      clustersToInit(service) = cluster
    }
    log.info(s"registered clusters: $clustersToInit")
    clustersToInit
  }

  def serviceCluster(serviceType: ServiceType): ServiceCluster = clusters(serviceType)

  def isLeader: Boolean = {
    val myCluster = clusters(services.currentService)
    val registered = myNode map {node => myCluster.registered(node)} getOrElse false
    if (!registered) {
      log.warn(s"service did not register itself yet!")
      return false
    }
    myCluster.leader match {
      case Some(instance) if instance.node == myNode.get =>
        require(myCluster.size > 0)
        return true
      case Some(instance)  =>
        require(myCluster.size > 1)
        log.info(s"I'm not the leader since my node is ${myNode.get} and the leader is ${instance.node}")
        return false
      case None =>
        require(myCluster.size == 0)
        return false
    }
  }

  override def toString(): String = clusters.map(kv => kv._1.toString + ":" + kv._2.toString).mkString("\n")

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  override def myClusterSize: Int = clusters.get(services.currentService) map {c => c.size} getOrElse 0

  private def stillRegistered(): Boolean = myNode map { clusters(services.currentService).instanceForNode(_).isDefined } getOrElse true


  private def keepAlive() : Unit = {
    scheduler.scheduleOnce(2 minutes){
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



  def register(): Node = {
    watchServices()
    val myServiceType: ServiceType = services.currentService
    log.info(s"registered clusters: $clusters, my service is $myServiceType")
    val myCluster = clusters(myServiceType)
    val instanceInfo = amazonInstanceInfoProvider.get
    val thisRemoteService = RemoteService(instanceInfo, ServiceStatus.STARTING, myServiceType)
    myNode = Some(zk.createNode(myCluster.serviceNodeMaster, RemoteService.toJson(thisRemoteService), EPHEMERAL_SEQUENTIAL))
    myCluster.register(myNode.get, thisRemoteService)
    log.info(s"registered as node ${myNode.get}")
    keepAlive()
    myNode.get
  }

  override def unRegister(): Unit = myNode map {node => zk.deleteNode(node)}

  def changeStatus(newStatus: ServiceStatus) : Unit = {
    myNode.map { node => 
      val thisServiceInstance = clusters(services.currentService).instanceForNode(node)
      thisServiceInstance.foreach{ serviceInstance =>
        log.info(s"Changing instance status to $newStatus")
        serviceInstance.remoteService.status = newStatus
        zk.set(node, RemoteService.toJson(serviceInstance.remoteService))
      }
    }
  }

  def myStatus : Option[ServiceStatus] = {
    myNode.flatMap { node =>
      val thisServiceInstance = clusters(services.currentService).instanceForNode(node)
      thisServiceInstance.map(_.remoteService.status)
    }
  }

  def myVersion: ServiceVersion = services.currentVersion 

  def startSelfCheck(): Unit = future {

    log.info("Running self check")
    services.currentService.selfCheck().onComplete{
      case Success(passed) => if (passed) changeStatus(ServiceStatus.UP) else changeStatus(ServiceStatus.SELFCHECK_FAIL)
      case Failure(e) => changeStatus(ServiceStatus.SELFCHECK_FAIL)
    }
  }
  

  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val serviceStatusFormat = ServiceStatus.format
  implicit val ipAddressFormat = Json.format[IpAddress]
  implicit val serviceTypeFormat = ServiceType.format
  

}

