package com.keepit.common.service

import play.api.libs.json._
import play.api.mvc.RequestHeader
import java.lang.{ Long => JLong }

import scala.util.Try

object IpAddress {
  val ipv4Pattern = """^((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])$""".r
  val ipv6Pattern = """^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$""".r

  implicit val format: Format[IpAddress] = Format(__.read[String].map(IpAddress(_)), Writes(ip => JsString(ip.ip)))

  def ipToLong(ipAddress: IpAddress): Long = {
    ipAddress match {
      case IpV4Address(ip) =>
        val octets = ip.split("\\.")
        octets.reverse.zipWithIndex.map { case (x, i) => x.toLong * Math.pow(256, i).toLong }.sum
      case IpV6Address(ip) =>
        // This is lossy, but compresses a ipv6 in 64 bits
        ip.split(":").map(ds => Try(JLong.parseLong(ds, 16)).getOrElse(0L)).reverse.zipWithIndex.map { case (x, i) => x * Math.pow(65536, i).toLong }.sum
    }
  }

  def longToIp(long: Long): IpAddress = {
    // Not perfect, but does pull IPv4 addresses out correctly.
    // Notice, however, that there are way more IPv6 addresses than longs. So, longToIp(ipToLong(ip)) != ip for nearly all IPv6s.
    if (long > 0L && long < 4294967295L) { // IPv4
      IpAddress(((long >> 24) & 0xFF) + "." + ((long >> 16) & 0xFF) + "." + ((long >> 8) & 0xFF) + "." + (long & 0xFF))
    } else {
      IpAddress("0:0:0:0:" + ((long >> 48) & 0xFFFF).toHexString + ":" + ((long >> 32) & 0xFFFF).toHexString + ":" + ((long >> 16) & 0xFFFF).toHexString + ":" + (long & 0xFFFF).toHexString)
    }
  }

  def fromRequest(request: RequestHeader) = {
    request.headers.get("X-Forwarded-For").flatMap(fromXForwardedFor).getOrElse(IpAddress(request.remoteAddress))
  }

  def fromXForwardedFor(xForwardedFor: String): Option[IpAddress] = {
    xForwardedFor.split(",").map(_.trim()).sortBy(_.startsWith("10.")).headOption.map(IpAddress.apply)
  }

  def apply(ip: String): IpAddress = {
    ip match {
      case ipv4Pattern(_*) => new IpV4Address(ip)
      case ipv6Pattern(_*) => new IpV6Address(ip)
      case _ => throw new IllegalArgumentException(s"ip address $ip does not match ip pattern")
    }
  }

  def unapply(ip: IpAddress) = Some(ip.ip)
}

sealed trait IpAddress {
  def ip: String
  def datacenterIp: Boolean = ip.startsWith("10.")
  override def toString(): String = ip
}
case class IpV4Address(ip: String) extends IpAddress
case class IpV6Address(ip: String) extends IpAddress

