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

trait ServiceDiscovery {
  def register(): Node
  def isLeader(): Boolean
  def myClusterSize: Int = 0
}

@Singleton
class ServiceDiscoveryImpl @Inject() (
    zk: ZooKeeperClient,
    services: FortyTwoServices,
    amazonInstanceInfo: AmazonInstanceInfo)
  extends ServiceDiscovery with Logging {

  private val serviceType = services.currentService
  private val myServicePath = Path(s"/fortytwo/services/${serviceType.name}")
  private val myServiceNodeMaster = Node(s"${myServicePath.name}/${serviceType.name}_")
  private var myNode: Option[Node] = None
  private var cluster = new TrieMap[Node, AmazonInstanceInfo]()

  private def myId: Option[Long] = myNode map extractId
  private def extractId(node: Node) = node.name.substring(node.name.lastIndexOf('_') + 1).toLong

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  //without me
  override def myClusterSize: Int = cluster.size

  def register(): Node = {
    val path = zk.createPath(myServicePath)
    myNode = Some(zk.createNode(myServiceNodeMaster, null, EPHEMERAL_SEQUENTIAL))
    zk.watchChildren(myServicePath, { (children : Seq[Node]) =>
      println(s"""services in my cluster under ${myServicePath.name}: ${children.mkString(", ")}""")
      future {
        try {
          val childNodes = children map {c => Node(s"${myServicePath.name}/$c")} filter { childNode =>
            println(s"discovered new node $childNode in my cluster")
            childNode.name != myNode.get.name
          }
          println(s"found ${childNodes.size} nodes in cluster")
          childNodes foreach { childNode =>
            cluster.getOrElseUpdate(childNode, {
              val json = Json.parse(zk.get(childNode))
              val amazonInstanceInfo = Json.fromJson[AmazonInstanceInfo](json).get
              println(s"discovered new node $childNode in my cluster: $amazonInstanceInfo")
              amazonInstanceInfo
            })
          }
          cluster.keys foreach { key =>
            if (!childNodes.contains(key)) {
              println(s"node $key is not in cluster anymore")
              cluster.remove(key)
            }
          }
        } catch {
          case e: Throwable => e.printStackTrace()
        }
      }
    })
    zk.set(myNode.get, Json.toJson(amazonInstanceInfo).toString)
    println(s"registered as node ${myNode.get}")
    myNode.get
  }

  def isLeader(): Boolean = myId map { id =>
    val siblings = zk.getChildren(myServicePath)
    val siblingsIds = siblings map extractId
    val minId = siblingsIds.min
    val isMinid = minId == id
    log.info(s"my service id is $id, service with id $minId is the leader => I'm the leader == $isMinid")
    isMinid
  } getOrElse (throw new IllegalStateException("service did not register yet"))

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

