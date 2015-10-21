package com.keepit.common.service

import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import scala.util.{ Random, Try }

class IpAddressTest extends Specification {

  "IpAddress" should {

    "match pattern" in {
      IpAddress("192.168.1.2").ip === "192.168.1.2"
    }

    "no match bad pattern" in {
      IpAddress("kifi.com") must throwAn[IllegalArgumentException]
    }

    "parse X-Forwarded-For header" in {
      IpAddress.fromXForwardedFor("10.1.1.1, 192.168.1.1, 222.222.222.3") === Some(IpAddress("192.168.1.1"))
      IpAddress.fromXForwardedFor("10.1.1.1, 2001:0db8:85a3:0000:0000:8a2e:0370:7334") === Some(IpAddress("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
    }

    "parse IPv4 addresses" in {
      IpAddress("222.222.222.3") === IpV4Address("222.222.222.3")
    }

    "parse IPv6 addresses" in {
      IpAddress("2001:0db8:85a3:0000:0000:8a2e:0370:7334") === IpV6Address("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
    }

    "preserve identity in long mapping for ipv4" in {
      IpAddress.longToIp(IpAddress.ipToLong(IpAddress("192.168.0.1"))) === IpV4Address("192.168.0.1")
    }

    "do best in long mapping for ipv6" in {
      IpAddress.longToIp(IpAddress.ipToLong(IpAddress("0:0:0:0:0:8a2e:36f:bfd8"))) === IpV6Address("0:0:0:0:0:8a2e:36f:bfd8")
      IpAddress.ipToLong(IpAddress("0:0:0:0:0:8a2e:36f:bfd8")) !== IpAddress.ipToLong(IpAddress("0:0:0:0:0:8a2e:36f:bfd9"))
      IpAddress.ipToLong(IpAddress("1:0:0:0:0:8a2e:36f:bfd8")) !== IpAddress.ipToLong(IpAddress("0:0:0:0:0:8a2e:36f:bfd8"))
    }
  }
}
