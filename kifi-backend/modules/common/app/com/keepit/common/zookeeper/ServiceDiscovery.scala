package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.collection.concurrent.TrieMap

import play.api.libs.json._

import com.google.inject.{Inject, Singleton}

import org.apache.zookeeper.CreateMode._
import com.google.inject.Provider

trait ServiceDiscovery {
  def register(): Node
  def isLeader(): Boolean
  def myClusterSize: Int = 0
}

@Singleton
class ServiceDiscoveryImpl @Inject() (
    zk: ZooKeeperClient,
    services: FortyTwoServices,
    amazonInstanceInfoProvider: Provider[AmazonInstanceInfo])
  extends ServiceDiscovery with Logging {

  private var myNode: Option[Node] = None

  private var clusters = {
    val clustersToInit = new TrieMap[ServiceType, ServiceCluster]()
    //the following should be configurable
    val servicesToListenOn = ServiceType.SEARCH :: ServiceType.SHOEBOX :: Nil
    servicesToListenOn foreach {service =>
      val cluster = new ServiceCluster(service)
      clustersToInit(service) = cluster
    }
    clustersToInit
  }

  def isLeader: Boolean = clusters(services.currentService).leader map (_.node == myNode.get) getOrElse false

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  override def myClusterSize: Int = clusters.get(services.currentService) map {c => c.size} getOrElse 0

  private def watchServices(): Unit = clusters.values foreach watchService

  private def watchService(cluster: ServiceCluster): Unit = {
    zk.createPath(cluster.servicePath)
    zk.watchChildren(cluster.servicePath, { (children : Seq[Node]) =>
      println(s"""services in my cluster under ${cluster.servicePath.name}: ${children.mkString(", ")}""")
      future {
        cluster.update(zk, children)
      }
    })
  }

  def register(): Node = {
    watchServices()
    myNode = Some(zk.createNode(clusters(services.currentService).serviceNodeMaster, null, EPHEMERAL_SEQUENTIAL))
    zk.set(myNode.get, Json.toJson(amazonInstanceInfoProvider.get).toString)
    println(s"registered as node ${myNode.get}")
    myNode.get
  }

  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val serviceStatusFormat = ServiceStatus.format
  implicit val ipAddressFormat = Json.format[IpAddress]
  implicit val serviceTypeFormat = ServiceType.format
  implicit val remoteServiceFormat = Json.format[RemoteService]

  def toRemoteService(data: Array[Byte]): RemoteService = Json.fromJson[RemoteService](Json.parse(data)).get
  def fromRemoteService(remote: RemoteService): Array[Byte] = Json.toJson[RemoteService](remote).toString
}

