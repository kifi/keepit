package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._

import play.api.libs.json._

import com.google.inject.{Inject, Singleton}
import com.keepit.common.net.ServiceUnavailableException
import java.util.concurrent.atomic.AtomicInteger

case class ServiceInstanceId(id: Long) {
  override def toString(): String = id.toString
}

//thisInstance means the representation of the current running instance
class ServiceInstance(val node: Node, val thisInstance: Boolean) extends Logging {

  private var remoteServiceOpt: Option[RemoteService] = None
  val sentServiceUnavailable = new AtomicInteger(0)

  def remoteService: RemoteService = remoteServiceOpt.get

  def setRemoteService(service: RemoteService): ServiceInstance = synchronized {
    remoteServiceOpt = Some(service)
    sentServiceUnavailable.set(0)
    this
  }

  lazy val id: ServiceInstanceId = ServiceInstanceId(node.name.substring(node.name.lastIndexOf('_') + 1).toLong)

  def sentServiceUnavailableException(e: ServiceUnavailableException) = {
    log.warn(s"marking service $remoteService as sentServiceUnavailableException for the ${sentServiceUnavailable.get} time")
    sentServiceUnavailable.incrementAndGet()
  }

  def instanceInfo : AmazonInstanceInfo = remoteService.amazonInstanceInfo

  def isHealthy : Boolean = (remoteService.status == remoteService.healthyStatus) && sentServiceUnavailable == 0

  def isUp: Boolean = remoteService.status == ServiceStatus.UP

  def isAvailable : Boolean = isUp || isAlmostUp

  private def isAlmostUp: Boolean = remoteService.healthyStatus == ServiceStatus.UP && (remoteService.status == ServiceStatus.SICK || remoteService.status == ServiceStatus.SELFCHECK_FAIL)
}


object ServiceInstance {
  def EMPTY = new ServiceInstance(null, true)
}