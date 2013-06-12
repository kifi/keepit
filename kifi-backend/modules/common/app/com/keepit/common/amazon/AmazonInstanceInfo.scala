package com.keepit.common.amazon

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.google.inject.{Inject, Singleton}

import com.keepit.common.time._
import com.keepit.common.service.IpAddress

case class AmazonInstanceId(id: String) extends AnyVal {
  override def toString(): String = id
}

object AmazonInstanceInfo {

  implicit val amazonInstanceIdFormat = Json.format[AmazonInstanceId]
  implicit val ipAddressFormat = Json.format[IpAddress]

  implicit val format: Format[AmazonInstanceInfo] = (
    (__ \ 'instanceId).format[AmazonInstanceId] and
    (__ \ 'localHostname).format[String] and
    (__ \ 'publicHostname).format[String] and
    (__ \ 'localIp).format[IpAddress] and
    (__ \ 'publicIp).format[IpAddress] and
    (__ \ 'instanceType).format[String] and
    (__ \ 'availabilityZone).format[String] and
    (__ \ 'securityGroups).format[String] and
    (__ \ 'amiId).format[String] and
    (__ \ 'amiLaunchIndex).format[String]
  )(AmazonInstanceInfo.apply, unlift(AmazonInstanceInfo.unapply))
}

case class AmazonInstanceInfo (
  instanceId: AmazonInstanceId,
  localHostname: String,
  publicHostname: String,
  localIp: IpAddress,
  publicIp: IpAddress,
  instanceType: String,
  availabilityZone: String,
  securityGroups: String,
  amiId: String,
  amiLaunchIndex: String
)
