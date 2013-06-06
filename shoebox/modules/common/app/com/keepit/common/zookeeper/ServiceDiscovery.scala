package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import play.api.libs.json._
import com.google.inject.{Inject, Singleton}
import org.apache.zookeeper.CreateMode._

trait ServiceDiscovery {
  def register(): Node
  def isLeader(): Boolean
}

@Singleton
class ServiceDiscoveryImpl @Inject() (
    zk: ZooKeeperClient,
    services: FortyTwoServices)
  extends ServiceDiscovery with Logging {

  val serviceType = services.currentService
  val myServicePath = Path(s"/services/${serviceType.name}")
  val myServiceNodeMaster = Node(s"${myServicePath.name}/${serviceType.name}_")
  var myNode: Option[Node] = None
  def myId: Option[Long] = myNode map extractId
  def extractId(node: Node) = node.name.substring(node.name.lastIndexOf('_') + 1).toLong

  def register(): Node = {
    val path = zk.createPath(myServicePath)
    zk.watchChildren(path, { (children : Seq[Node]) =>
      log.info(s"""services in my cluster: ${children.mkString(", ")}""")
    })
    myNode = Some(zk.createNode(myServiceNodeMaster, null, EPHEMERAL_SEQUENTIAL))
    log.info(s"registered as node $myNode")
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

