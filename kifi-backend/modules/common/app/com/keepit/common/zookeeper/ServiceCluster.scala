package com.keepit.common.zookeeper

import java.util.concurrent.atomic.AtomicInteger

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

class ServiceCluster(val serviceType: ServiceType) extends Logging {

  private var instances = new TrieMap[Node, ServiceInstance]()
  private var routingList: Vector[ServiceInstance] = Vector()
  private val nextRoutingInstance = new AtomicInteger(1)

  val servicePath = Path(s"/fortytwo/services/${serviceType.name}")
  val serviceNodeMaster = Node(s"${servicePath.name}/${serviceType.name}_")

  def size: Int = instances.size
  def registered(instance: ServiceInstance): Boolean = instances.contains(ensureFullPathNode(instance.node))
  var leader: Option[ServiceInstance] = None

  override def toString(): String = s"""Service Cluster of $serviceType:
    ${instances.toString}"""

  //using round robin, also use sick etc. instances if less than half of the instances ar UP.
  def nextService(): Option[ServiceInstance] = {
    val healthyList = routingList.filter(_.isHealthy)
    val availableList = routingList.filter(_.isAvailable)
    var list = healthyList
    if (healthyList.length < availableList.length/2.0) list = availableList
    if (list.isEmpty) None
    else Some(list(nextRoutingInstance.getAndIncrement % list.size))
  }

  def allServices: Vector[ServiceInstance] = routingList.filter(_.isAvailable)

  //This will includes all instances still registered with zookeeper including DOWN, STARTING, STOPPING states
  def allMembers : Vector[ServiceInstance] = routingList

  def register(instance: ServiceInstance): ServiceInstance = synchronized {
    instances(instance.node) = instance
    resetRoutingList()
    instance
  }

  def instanceForNode(node: Node) : Option[ServiceInstance] = instances.get(node)

  def ensureFullPathNode(node: Node, throwIfDoes: Boolean = false) = node.name contains servicePath.name match {
    case true if (throwIfDoes) => throw new Exception(s"node $node already contains service path")
    case true => node
    case false => Node(s"${servicePath.name}/$node")
  }

  private def addNewNode(newInstances: TrieMap[Node, ServiceInstance], childNode: Node, zk: ZooKeeperClient) = try {
    val nodeData: String = zk.get(childNode)
    log.info(s"data for node $childNode is $nodeData")
    val remoteService = RemoteService.fromJson(nodeData)
    if (newInstances.isDefinedAt(childNode)){
      log.info(s"discovered updated node $childNode: $remoteService, adding to ${newInstances.keys}")
      newInstances(childNode).remoteService = remoteService
    }
    else {
      log.info(s"discovered new node $childNode: $remoteService, adding to ${newInstances.keys}")
      newInstances(childNode) = ServiceInstance(serviceType, childNode, remoteService, false)
    }
  } catch {
    case t: Throwable =>
      log.error(s"could not fetch data node for instance of $childNode")
  }

  private def addNewNodes(newInstances: TrieMap[Node, ServiceInstance], childNodes: Seq[Node], zk: ZooKeeperClient) =
    childNodes foreach { childNode =>
      addNewNode(newInstances, childNode, zk)
    }

  private def removeOldNodes(newInstances: TrieMap[Node, ServiceInstance], childNodes: Seq[Node]) = newInstances.keys foreach { node =>
    if(!childNodes.contains(node)) {
      log.info(s"node $node is not in instances anymore: ${newInstances.keys}")
      newInstances.remove(node)
    }
  }

  private def findLeader(newInstances: TrieMap[Node, ServiceInstance]) = newInstances.isEmpty match {
    case true => None
    case false =>
      val minId = (newInstances.values map {v => v.id}).min
      val leader = newInstances.values.filter(_.id == minId).head
      log.info(s"leader is $leader")
      Some(leader)
  }

  def update(zk: ZooKeeperClient, children: Seq[Node]): Unit = synchronized {
    val newInstances = instances.clone()
    val childNodes = children map {c => ensureFullPathNode(c, true)}
    addNewNodes(newInstances, childNodes, zk)
    removeOldNodes(newInstances, childNodes)
    leader = findLeader(newInstances)
    instances = newInstances
    resetRoutingList()
  }

  private def resetRoutingList() =
    routingList = Vector(instances.values.toSeq: _*)
}
