package com.keepit.common.zookeeper

import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.service._

import play.api.libs.json._

object RemoteService {
  implicit val remoteServiceFormat = Json.format[RemoteService]

  def fromJson(data: String): RemoteService = Json.fromJson[RemoteService](Json.parse(data)).get
  def toJson(remote: RemoteService): String = Json.toJson[RemoteService](remote).toString
}

case class RemoteService(amazonInstanceInfo: AmazonInstanceInfo, status: ServiceStatus, serviceType: ServiceType) {
  def healthyStatus: ServiceStatus = serviceType.healthyStatus(amazonInstanceInfo)

  def getShardSpec: Option[String] = amazonInstanceInfo.tags.get("ShardSpec")
}

