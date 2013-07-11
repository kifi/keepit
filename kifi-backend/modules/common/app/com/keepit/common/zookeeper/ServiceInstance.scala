package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

import play.api.libs.json._

import com.google.inject.{Inject, Singleton}

case class ServiceInstance(serviceType: ServiceType, node: Node, var remoteService: RemoteService) extends Logging {
  lazy val id: Long = node.name.substring(node.name.lastIndexOf('_') + 1).toLong

  def instanceInfo : AmazonInstanceInfo = remoteService.amazonInstanceInfo

  def isAvailable : Boolean = isHealthy || remoteService.status==ServiceStatus.SICK || remoteService.status==ServiceStatus.SELFCHECK_FAIL

  def isHealthy : Boolean = true //remoteService.status==ServiceStatus.UP

}
