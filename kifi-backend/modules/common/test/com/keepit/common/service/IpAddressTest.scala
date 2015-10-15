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
    }
  }
}
