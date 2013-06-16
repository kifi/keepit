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

class ServiceCluster(serviceType: ServiceType, myServicePath: Path) extends Logging {

  private var instances = new TrieMap[Node, ServiceInstance]()

  def size: Int = instances.size
  var leader: Option[ServiceInstance] = None

  private def toFullPathNodes(nodes: Seq[Node]) = nodes map {c => Node(s"${myServicePath.name}/$c")}

  private def addNewNodes(newInstances: TrieMap[Node, ServiceInstance], childNodes: Seq[Node], zk: ZooKeeperClient) = childNodes foreach { childNode =>
    newInstances.getOrElseUpdate(childNode, {
      val nodeData: String = zk.get(childNode)
      val json = Json.parse(nodeData)
      val amazonInstanceInfo = Json.fromJson[AmazonInstanceInfo](json).get
      log.info(s"discovered new node $childNode in my instances: $amazonInstanceInfo")
      ServiceInstance(serviceType, childNode, amazonInstanceInfo)
    })
  }

  private def removeOldNodes(newInstances: TrieMap[Node, ServiceInstance], childNodes: Seq[Node]) = newInstances.keys filter { node =>
    !childNodes.contains(node)
  } foreach { node =>
    println(s"node $node is not in instances anymore")
    newInstances.remove(node)
  }

  private def findLeader(newInstances: TrieMap[Node, ServiceInstance]) = newInstances.isEmpty match {
    case true => None
    case false =>
      val minId = (newInstances.values map {v => v.id}).min
      Some(newInstances.values.filter(_.id == minId).head)
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
