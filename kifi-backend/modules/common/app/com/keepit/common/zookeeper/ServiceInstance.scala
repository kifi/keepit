package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.service._
import com.keepit.common.amazon._
import java.util.concurrent.atomic.AtomicInteger

case class ServiceInstanceId(id: Long) {
  override def toString(): String = id.toString
}

/**
 * thisInstance means the representation of the current running instance
 */
class ServiceInstance(val node: Node, val thisInstance: Boolean, val remoteService: RemoteService) extends Logging {

  override def equals(other: Any): Boolean = {
    other match {
      case otherService: ServiceInstance => node == otherService.node
      case _ => false
    }
  }
  override def hashCode: Int = node.hashCode

  private val sentServiceUnavailable = new AtomicInteger(0)
  def reportedSentServiceUnavailable: Boolean = sentServiceUnavailable.get() != 0
  def reportedSentServiceUnavailableCount: Int = sentServiceUnavailable.get()

  override def toString() = s"Service Instance of zk node $node with remote service $remoteService"

  lazy val id: ServiceInstanceId = ServiceInstanceId(node.name.substring(node.name.lastIndexOf('_') + 1).toLong)

  def reportServiceUnavailable(): Int = if (remoteService.serviceType == ServiceType.DEV_MODE) 0 else {
    log.warn(s"marking service $remoteService as sentServiceUnavailableException for the ${sentServiceUnavailable.get} time")
    sentServiceUnavailable.incrementAndGet()
  }

  def instanceInfo: AmazonInstanceInfo = remoteService.amazonInstanceInfo

  def isHealthy: Boolean = remoteService.status == remoteService.healthyStatus

  def isUp: Boolean = (remoteService.status == ServiceStatus.UP) && !reportedSentServiceUnavailable

  def isAvailable: Boolean = isUp || isAlmostUp

  private def isAlmostUp: Boolean = remoteService.healthyStatus == ServiceStatus.UP && (remoteService.status == ServiceStatus.SICK || remoteService.status == ServiceStatus.SELFCHECK_FAIL)
}
