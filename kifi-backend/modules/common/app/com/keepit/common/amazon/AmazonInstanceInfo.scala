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
) {

  lazy val instantTypeInfo: AmazonInstanceType = instanceType match {
    case AmazonInstanceType.C1XLarge.name => AmazonInstanceType.C1XLarge
    case AmazonInstanceType.C3Large.name => AmazonInstanceType.C3Large
    case AmazonInstanceType.M1Large.name => AmazonInstanceType.M1Large
    case AmazonInstanceType.M1Medium.name => AmazonInstanceType.M1Medium
    case AmazonInstanceType.M1Small.name => AmazonInstanceType.M1Small
    case AmazonInstanceType.T1Micro.name => AmazonInstanceType.T1Micro
  }

}

sealed abstract class AmazonInstanceType(val name: String, val cores: Int, val ecu: Int)

object AmazonInstanceType {
  case object C1XLarge extends AmazonInstanceType("c1.xlarge", 8, 20)
  case object C3Large  extends AmazonInstanceType("c3.large", 2, 7)
  case object M1Large  extends AmazonInstanceType("m1.large", 2, 4)
  case object M1Medium extends AmazonInstanceType("m1.medium", 1, 2)
  case object M1Small  extends AmazonInstanceType("m1.small", 1, 1)
  case object T1Micro  extends AmazonInstanceType("t1.micro", 1, 1)//actually 1/2 of an ecu
}

