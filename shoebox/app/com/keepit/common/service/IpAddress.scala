package com.keepit.common.service

object IpAddress {
  val ipPattern = """^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"""
}

case class IpAddress(ip: String) {
  if (!ip.matches(IpAddress.ipPattern)) {
    throw new IllegalArgumentException(s"ip address $ip does not match ip pattern")
  }
  override def toString(): String = ip
}
