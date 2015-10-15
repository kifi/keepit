package com.keepit.common.service

import play.api.libs.json._

object IpAddress {
  val ipPattern = """^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"""
  implicit val format: Format[IpAddress] = Format(__.read[String].map(IpAddress(_)), Writes(ip => JsString(ip.ip)))

  implicit def ipToLong(ipAddress: IpAddress): Long = {
    val octets = ipAddress.ip.split("\\.")
    octets.reverse.zipWithIndex.map { case (x, i) => x.toLong * Math.pow(256, i).toLong }.sum
  }

  implicit def longToIp(long: Long): IpAddress = {
    IpAddress(((long >> 24) & 0xFF) + "." + ((long >> 16) & 0xFF) + "." + ((long >> 8) & 0xFF) + "." + (long & 0xFF))
  }

  def fromXForwardedFor(xForwardedFor: String): Option[IpAddress] = {
    xForwardedFor.split(",").map(_.trim()).sortBy(_.startsWith("10.")).headOption.map(IpAddress.apply)
  }
}

case class IpAddress(ip: String) {
  import IpAddress._
  if (!ip.matches(ipPattern)) {
    throw new IllegalArgumentException(s"ip address $ip does not match ip pattern")
  }
  def datacenterIp: Boolean = ip.toString.startsWith("10.")

  override def toString(): String = ip
}
