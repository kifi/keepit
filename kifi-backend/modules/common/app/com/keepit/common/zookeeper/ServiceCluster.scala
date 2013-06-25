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

class ServiceCluster(serviceType: ServiceType) extends Logging {

  private var instances = new TrieMap[Node, ServiceInstance]()

  val servicePath = Path(s"/fortytwo/services/${serviceType.name}")
  val serviceNodeMaster = Node(s"${servicePath.name}/${serviceType.name}_")

  def size: Int = instances.size
  def registered(node: Node): Boolean = instances.contains(node)
  var leader: Option[ServiceInstance] = None

  private def toFullPathNodes(nodes: Seq[Node]): Set[Node] = ( nodes map {c => Node(s"${servicePath.name}/$c")} ).toSet

  private def addNewNodes(newInstances: TrieMap[Node, ServiceInstance], childNodes: Set[Node], zk: ZooKeeperClient) = childNodes foreach { childNode =>
    newInstances.getOrElseUpdate(childNode, {
      val nodeData: String = zk.get(childNode)
      val json = Json.parse(nodeData)
      val amazonInstanceInfo = Json.fromJson[AmazonInstanceInfo](json).get
      println(s"discovered new node $childNode: $amazonInstanceInfo, adding to ${newInstances.keys}")
      ServiceInstance(serviceType, childNode, amazonInstanceInfo)
    })
  }

  private def removeOldNodes(newInstances: TrieMap[Node, ServiceInstance], childNodes: Set[Node]) = newInstances.keys foreach { node =>
    if(!childNodes.contains(node)) {
      println(s"node $node is not in instances anymore: ${newInstances.keys}")
      newInstances.remove(node)
    }
  }

  private def findLeader(newInstances: TrieMap[Node, ServiceInstance]) = newInstances.isEmpty match {
    case true => None
    case false =>
      val minId = (newInstances.values map {v => v.id}).min
      val leader = newInstances.values.filter(_.id == minId).head
      println(s"leader is $leader")
      Some(leader)
  }

  def update(zk: ZooKeeperClient, children: Seq[Node]): Unit = synchronized {
    try {
      val newInstances = instances.clone()
      val childNodes = toFullPathNodes(children)
      addNewNodes(newInstances, childNodes, zk)
      removeOldNodes(newInstances, childNodes)
      leader = findLeader(newInstances)
      instances = newInstances
    } catch {
      case e: Throwable => e.printStackTrace()
    }
  }
}
