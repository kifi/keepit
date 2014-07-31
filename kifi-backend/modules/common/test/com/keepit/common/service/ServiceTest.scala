package com.keepit.common.service

import com.keepit.test._
import com.keepit.test.CommonTestInjector
import org.specs2.mutable.Specification

class ServiceTest extends Specification with CommonTestInjector {

  "Service" should {

    "parse" in {
      val version = ServiceVersion("20140123-1713-HEAD-77f17ab")
      version.date === "20140123"
      version.time === "1713"
      version.branch === "HEAD"
      version.hash === "77f17ab"
    }

  }
}
