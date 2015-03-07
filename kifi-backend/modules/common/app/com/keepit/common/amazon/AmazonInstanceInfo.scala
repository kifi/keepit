package com.keepit.common.amazon

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.google.inject.{ Inject, Singleton }

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
    (__ \ 'name).formatNullable[String] and
    (__ \ 'service).formatNullable[String] and
    (__ \ 'localHostname).format[String] and
    (__ \ 'publicHostname).format[String] and
    (__ \ 'localIp).format[IpAddress] and
    (__ \ 'publicIp).format[IpAddress] and
    (__ \ 'instanceType).format[String] and
    (__ \ 'availabilityZone).format[String] and
    (__ \ 'securityGroups).format[String] and
    (__ \ 'amiId).format[String] and
    (__ \ 'amiLaunchIndex).format[String] and
    (__ \ 'loadBalancer).formatNullable[String] and
    (__ \ 'tags).formatNullable[Map[String, String]].inmap[Map[String, String]](_.getOrElse(Map.empty), Some(_))

  )(AmazonInstanceInfo.apply, unlift(AmazonInstanceInfo.unapply))
}

case class AmazonInstanceInfo(
    instanceId: AmazonInstanceId,
    name: Option[String],
    service: Option[String],
    localHostname: String,
    publicHostname: String,
    localIp: IpAddress,
    publicIp: IpAddress,
    instanceType: String,
    availabilityZone: String,
    securityGroups: String,
    amiId: String,
    amiLaunchIndex: String,
    loadBalancer: Option[String],
    tags: Map[String, String] = Map()) {

  def getName = name getOrElse instanceId.id

  lazy val capabilities: Set[String] = {
    tags.get("Capabilities") match {
      case Some(c) => c.split(",").map(_.trim).filter(_.length > 0).toSet
      case _ => Set()
    }
  }

  lazy val instantTypeInfo: AmazonInstanceType = instanceType match {
    case AmazonInstanceType.C1Medium.name => AmazonInstanceType.C1Medium
    case AmazonInstanceType.C1XLarge.name => AmazonInstanceType.C1XLarge
    case AmazonInstanceType.C3Large.name => AmazonInstanceType.C3Large
    case AmazonInstanceType.C3XLarge.name => AmazonInstanceType.C3XLarge
    case AmazonInstanceType.C3XXLarge.name => AmazonInstanceType.C3XXLarge
    case AmazonInstanceType.M3Medium.name => AmazonInstanceType.M3Medium
    case AmazonInstanceType.M3Large.name => AmazonInstanceType.M3Large
    case AmazonInstanceType.M3XLarge.name => AmazonInstanceType.M3XLarge
    case AmazonInstanceType.M3XXLarge.name => AmazonInstanceType.M3XXLarge
    case AmazonInstanceType.R3XLarge.name => AmazonInstanceType.R3XLarge
    case AmazonInstanceType.M1Large.name => AmazonInstanceType.M1Large
    case AmazonInstanceType.M1Medium.name => AmazonInstanceType.M1Medium
    case AmazonInstanceType.M1Small.name => AmazonInstanceType.M1Small
    case AmazonInstanceType.T1Micro.name => AmazonInstanceType.T1Micro
    case _ => AmazonInstanceType.UNKNOWN //we don't want to kill a cluster because we can't parse a machine type
  }

}

sealed abstract class AmazonInstanceType(val name: String, val cores: Int, val ecu: Int)

object AmazonInstanceType {
  // C1 class
  case object C1Medium extends AmazonInstanceType("c1.medium", 2, 5)
  case object C1XLarge extends AmazonInstanceType("c1.xlarge", 8, 20)

  // C3 class
  case object C3Large extends AmazonInstanceType("c3.large", 2, 7)
  case object C3XLarge extends AmazonInstanceType("c3.xlarge", 4, 14)
  case object C3XXLarge extends AmazonInstanceType("c3.2xlarge", 8, 24)

  // M3 class
  case object M3Medium extends AmazonInstanceType("m3.medium", 1, 3)
  case object M3Large extends AmazonInstanceType("m3.large", 2, 6)
  case object M3XLarge extends AmazonInstanceType("m3.xlarge", 4, 13)
  case object M3XXLarge extends AmazonInstanceType("m3.2xlarge", 8, 26)

  // R3 class
  case object R3XLarge extends AmazonInstanceType("r3.xlarge", 4, 13)

  // M1 class
  case object M1Small extends AmazonInstanceType("m1.small", 1, 1)
  case object M1Medium extends AmazonInstanceType("m1.medium", 1, 2)
  case object M1Large extends AmazonInstanceType("m1.large", 2, 4)

  // T1 class
  case object T1Micro extends AmazonInstanceType("t1.micro", 1, 1) //actually 1/2 of an ecu
  case object UNKNOWN extends AmazonInstanceType("UNKNOWN", 2, 4)
}

