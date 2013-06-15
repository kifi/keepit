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

  def update(zk: ZooKeeperClient, children: Seq[Node]): Unit = try {
    val childNodes = children map {c => Node(s"${myServicePath.name}/$c")}
    childNodes foreach { childNode =>
      instances.getOrElseUpdate(childNode, {
        val nodeData: String = zk.get(childNode)
        val json = Json.parse(nodeData)
        val amazonInstanceInfo = Json.fromJson[AmazonInstanceInfo](json).get
        log.info(s"discovered new node $childNode in my instances: $amazonInstanceInfo")
        ServiceInstance(serviceType, childNode, amazonInstanceInfo)
      })
    }
    instances.keys filter { node => !childNodes.contains(node) } foreach { node =>
      println(s"node $node is not in instances anymore")
      instances.remove(node)
    }
    val minId = (instances.values map {v => v.id}).min
    leader = Some(instances.values.filter(_.id == minId).head)
  } catch {
    case e: Throwable => e.printStackTrace()
  }
}
