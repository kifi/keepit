package com.keepit.common.zookeeper

import java.util.concurrent.atomic.AtomicInteger

import com.keepit.common.logging.Logging
import com.keepit.common.service._
import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap

import akka.actor.{ Scheduler, Cancellable }

import com.google.inject.Provider

class ServiceCluster(val serviceType: ServiceType, airbrake: Provider[AirbrakeNotifier], scheduler: Scheduler, forceUpdateTopology: () => Unit) extends Logging {

  @volatile private[this] var instances = new TrieMap[Node, ServiceInstance]()
  @volatile private[this] var routingList: Vector[ServiceInstance] = Vector()
  private[this] val nextRoutingInstance = new AtomicInteger(1)

  private[this] var scheduledWarning: Option[Cancellable] = None
  private[this] var scheduledPanic: Option[Cancellable] = None

  val servicePath = Node(s"/fortytwo/services/${serviceType.name}")

  def size: Int = instances.size
  def registered(instance: ServiceInstance): Boolean = instances.contains(instance.node)
  var leader: Option[ServiceInstance] = None

  override def toString(): String = s"""Service Cluster of $serviceType:
    ${instances.toString}"""

  /**
   * using round robin, also use sick etc. instances if less than half of the instances ar UP.
   */
  def nextService(serviceStatus: Option[ServiceStatus] = None): Option[ServiceInstance] = {
    val upList = serviceStatus.map(stat => routingList.filter(_.remoteService.status == stat)).getOrElse(routingList.filter(_.isUp))
    val availableList = routingList.filter(_.isAvailable)
    val list = if (upList.length < availableList.length / 2.0) {
      availableList
    } else {
      upList
    }
    if (list.isEmpty) {
      forceUpdateTopology()
      None
    } else {
      Some(list(nextRoutingInstance.getAndIncrement % list.size))
    }
  }

  def allServices: Vector[ServiceInstance] = routingList.filter(_.isAvailable)

  //This will includes all instances still registered with zookeeper including DOWN, STARTING, STOPPING states
  def allMembers: Vector[ServiceInstance] = routingList

  def register(instance: ServiceInstance): ServiceInstance = synchronized {
    instances(instance.node) = instance
    resetRoutingList()
    instance
  }

  def instanceForNode(node: Node): Option[ServiceInstance] = instances.get(node)

  private def addNewNode(newInstances: TrieMap[Node, ServiceInstance], childNode: Node, nodeData: String) = try {
    log.info(s"data for node $childNode is $nodeData")
    val remoteService = RemoteService.fromJson(nodeData)
    newInstances(childNode) = newInstances.get(childNode) match {
      case Some(instance) =>
        log.info(s"discovered updated node $childNode: $remoteService, adding to ${newInstances.keys}")
        new ServiceInstance(childNode, instance.thisInstance, remoteService)
      case None =>
        log.info(s"discovered new node $childNode: $remoteService, adding to ${newInstances.keys}")
        new ServiceInstance(childNode, false, remoteService)
    }
  } catch {
    case t: Throwable =>
      log.error(s"could not fetch data node for instance of $childNode: ${t.toString}", t)
  }

  private def addNewNodes(newInstances: TrieMap[Node, ServiceInstance], children: Seq[(Node, String)]) = {
    children foreach { case (childNode, nodeData) => addNewNode(newInstances, childNode, nodeData) }
  }

  private def removeOldNodes(newInstances: TrieMap[Node, ServiceInstance], childNodes: Seq[Node]) = newInstances.keys foreach { node =>
    if (!childNodes.contains(node)) {
      log.info(s"node $node is not in instances anymore: ${newInstances.keys}")
      newInstances.remove(node)
    }
  }

  private def findLeader(newInstances: TrieMap[Node, ServiceInstance]) = newInstances.isEmpty match {
    case true => None
    case false =>
      val minId = ServiceInstanceId((newInstances.values map { v => v.id.id }).min)
      val leader = newInstances.values.filter(_.id == minId).head
      log.info(s"leader is $leader")
      Some(leader)
  }

  def deDuplicate(zk: ZooKeeperSession, instances: TrieMap[Node, ServiceInstance]): TrieMap[Node, ServiceInstance] = {
    try {
      val machines = new TrieMap[IpAddress, Node]()
      instances foreach {
        case (node, instance) =>
          val ip = instance.instanceInfo.localIp
          machines.get(ip) foreach { existing =>
            airbrake.get.notify(s"there are two existing ZK nodes with the same IP address $ip: $existing and $node for service ${instance}, removing the smallest node")
            val newNodeId = instances(node).id.id
            val existingNodeId = instances(existing).id.id
            if (newNodeId == existingNodeId) {
              airbrake.get.notify(s"The two existing ZK nodes have same node ID! Don't know what to do $ip: $existing and $node for service ${instance}, breaking out")
            } else if (newNodeId < existingNodeId) {
              zk.delete(node)
              instances.remove(node)
            } else {
              zk.delete(existing)
              instances.remove(existing)
            }
          }
          machines(instance.instanceInfo.localIp) = node
      }
    } catch {
      //dedup should not break the discovery service!
      case e: Exception => airbrake.get.notify(e)
    }
    instances
  }

  def update(zk: ZooKeeperSession, children: Seq[(Node, String)]): Unit = synchronized {
    val newInstances = instances.clone()
    addNewNodes(newInstances, children)
    removeOldNodes(newInstances, children.map(_._1))
    deDuplicate(zk, newInstances)
    leader = findLeader(newInstances)
    instances = newInstances
    resetRoutingList()
    if (instances.size < serviceType.minInstances) {
      if (scheduledPanic.isEmpty) {
        scheduledPanic = Some(scheduler.scheduleOnce(3 minutes) {
          airbrake.get.panic(s"Service cluster for $serviceType way too small!")
        })
      }
    } else if (instances.size < serviceType.warnInstances) {
      if (scheduledWarning.isEmpty) {
        scheduledWarning = Some(scheduler.scheduleOnce(20 minutes) {
          airbrake.get.notify(s"Service cluster for $serviceType too small!")
        })
      }
    } else {
      scheduledWarning.map(_.cancel())
      scheduledPanic.map(_.cancel())
      scheduledWarning = None
      scheduledPanic = None
    }
  }

  private def resetRoutingList() = {
    routingList = instances.values.toVector.sortBy(_.node.path)

    customRouter.foreach { myRouter =>
      try {
        myRouter.update(routingList, forceUpdateTopology)
      } catch {
        case e: Throwable =>
          log.error("custom router update failed", e)
          airbrake.get.notify(s"custom router update failed: serviceType=$serviceType customRouter=${customRouter.getClass.getSimpleName}")
      }
    }
  }

  @volatile
  private[this] var customRouter: Option[CustomRouter] = None

  def setCustomRouter(myRouter: Option[CustomRouter]): Unit = synchronized {
    myRouter.foreach(_.update(routingList, forceUpdateTopology))
    customRouter = myRouter
  }

  def getCustomRouter: Option[CustomRouter] = customRouter

  def refresh() = forceUpdateTopology()
}
