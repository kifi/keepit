package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.collection.concurrent.TrieMap

import play.api.libs.json._

import com.google.inject.{Inject, Singleton, Provider}

import org.apache.zookeeper.CreateMode._

trait ServiceDiscovery {
  def serviceCluster(serviceType: ServiceType): ServiceCluster
  def register(): Node
  def unRegister(): Unit = {}
  def isLeader(): Boolean
  def myClusterSize: Int = 0
}

@Singleton
class ServiceDiscoveryImpl @Inject() (
    zk: ZooKeeperClient,
    services: FortyTwoServices,
    amazonInstanceInfoProvider: Provider[AmazonInstanceInfo],
    servicesToListenOn: Seq[ServiceType] = ServiceType.SEARCH :: ServiceType.SHOEBOX :: Nil)
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
        return true
    }
  }

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  override def myClusterSize: Int = clusters.get(services.currentService) map {c => c.size} getOrElse 0

  private def watchServices(): Unit = clusters.values foreach watchService

  private def watchService(cluster: ServiceCluster): Unit = {
    zk.createPath(cluster.servicePath)
    zk.watchChildren(cluster.servicePath, { (children : Seq[Node]) =>
      log.info(s"""services in my cluster under ${cluster.servicePath.name}: ${children.mkString(", ")}""")
      future {
        cluster.update(zk, children)
      }
    })
  }

  def register(): Node = {
    watchServices()
    val myServiceType: ServiceType = services.currentService
    log.info(s"registered clusters: $clusters, my service is $myServiceType")
    val myCluster = clusters(myServiceType)
    val instanceInfo = amazonInstanceInfoProvider.get
    myNode = Some(zk.createNode(myCluster.serviceNodeMaster, Json.toJson(instanceInfo).toString, EPHEMERAL_SEQUENTIAL))
    myCluster.register(myNode.get, instanceInfo)
    log.info(s"registered as node ${myNode.get}")
    myNode.get
  }

  override def unRegister(): Unit = myNode map {node => zk.deleteNode(node)}

  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val serviceStatusFormat = ServiceStatus.format
  implicit val ipAddressFormat = Json.format[IpAddress]
  implicit val serviceTypeFormat = ServiceType.format
  implicit val remoteServiceFormat = Json.format[RemoteService]

  def toRemoteService(data: Array[Byte]): RemoteService = Json.fromJson[RemoteService](Json.parse(data)).get
  def fromRemoteService(remote: RemoteService): Array[Byte] = Json.toJson[RemoteService](remote).toString
}

