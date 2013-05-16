package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import play.api.libs.json._
import com.google.inject.{Inject, Singleton}
import org.apache.zookeeper.CreateMode._

@Singleton
class ServiceDiscovery @Inject() (zk: ZooKeeperClient, services: FortyTwoServices) extends Logging {

  val serviceType = services.currentService
  val myServicePath = Path(s"/services/${serviceType.name}")
  val myServiceNodeMaster = Node(s"${myServicePath.name}/${serviceType.name}_")

  def register(): Node = {
    val path = zk.createPath(myServicePath)
    zk.watchChildren(path, { (children : Seq[Node]) =>
      log.info(s"""services in my cluster: ${children.mkString(", ")}""")
    })
    zk.createNode(myServiceNodeMaster, null, EPHEMERAL_SEQUENTIAL)
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

