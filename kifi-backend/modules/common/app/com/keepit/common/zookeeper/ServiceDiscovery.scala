package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._
<<<<<<< HEAD

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.collection.concurrent.TrieMap

=======
>>>>>>> e415d5468dd22a0f7af246d36be37eb92523a31e
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

  private val serviceType = services.currentService
  private val myServicePath = Path(s"/fortytwo/services/${serviceType.name}")
  private val myServiceNodeMaster = Node(s"${myServicePath.name}/${serviceType.name}_")
  private var myNode: Option[Node] = None

  private var clusters = new TrieMap[Path, ServiceCluster]()
  val isLeader: Boolean = clusters(myServicePath).leader map (_.node == myNode.get) getOrElse false

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  //without me
  override def myClusterSize: Int = clusters(myServicePath).size

  def register(): Node = {
    val path = zk.createPath(myServicePath)
    myNode = Some(zk.createNode(myServiceNodeMaster, null, EPHEMERAL_SEQUENTIAL))
    zk.watchChildren(myServicePath, { (children : Seq[Node]) =>
      println(s"""services in my cluster under ${myServicePath.name}: ${children.mkString(", ")}""")
      future {
        val cluster = clusters.getOrElseUpdate(myServicePath, new ServiceCluster(serviceType, myServicePath))
        cluster.update(zk, children)
      }
    })
    zk.set(myNode.get, Json.toJson(amazonInstanceInfoProvider.get).toString)
    println(s"registered as node ${myNode.get}")
    myNode.get
  }

  def watchNode(node: Node) {
    zk.watchNode(node, { (data : Option[Array[Byte]]) =>
      data match {
        case Some(data) =>
          val service = toRemoteService(data)
          //do something with service...
        case None => //nothing to do...
      }
    })
  }


  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val serviceStatusFormat = ServiceStatus.format
  implicit val ipAddressFormat = Json.format[IpAddress]
  implicit val serviceTypeFormat = ServiceType.format
  implicit val remoteServiceFormat = Json.format[RemoteService]

  def toRemoteService(data: Array[Byte]): RemoteService = Json.fromJson[RemoteService](Json.parse(data)).get
  def fromRemoteService(remote: RemoteService): Array[Byte] = Json.toJson[RemoteService](remote).toString
}

