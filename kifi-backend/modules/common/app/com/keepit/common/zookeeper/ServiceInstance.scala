package com.keepit.common.zookeeper

import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.service._
import com.keepit.common.amazon._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

import play.api.libs.json._

import com.google.inject.{Inject, Singleton}

case class ServiceInstance(serviceType: ServiceType, node: Node, amazonInstanceInfo: AmazonInstanceInfo) extends Logging {
  lazy val id: Long = node.name.substring(node.name.lastIndexOf('_') + 1).toLong

}
