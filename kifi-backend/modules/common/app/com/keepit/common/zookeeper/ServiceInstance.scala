package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._

import play.api.libs.json._

import com.google.inject.{Inject, Singleton}

case class ServiceInstanceId(id: Long) {
  override def toString(): String = id.toString
}

//thisInstance means the representation of the current running instance
case class ServiceInstance(node: Node, var remoteService: RemoteService, thisInstance: Boolean) extends Logging {

  lazy val id: ServiceInstanceId = ServiceInstanceId(node.name.substring(node.name.lastIndexOf('_') + 1).toLong)

  def instanceInfo : AmazonInstanceInfo = remoteService.amazonInstanceInfo

  def isHealthy : Boolean = remoteService.status == remoteService.healthyStatus

  def isUp: Boolean = remoteService.status == ServiceStatus.UP

  def isAvailable : Boolean = isUp || isAlmostUp

  private def isAlmostUp: Boolean = remoteService.healthyStatus == ServiceStatus.UP && (remoteService.status == ServiceStatus.SICK || remoteService.status == ServiceStatus.SELFCHECK_FAIL)
}


object ServiceInstance {
  def EMPTY = ServiceInstance(null, null, true)
}