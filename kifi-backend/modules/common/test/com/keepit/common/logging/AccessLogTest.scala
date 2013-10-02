package com.keepit.common.logging

import com.keepit.test._
import org.specs2.mutable.Specification

class AccessLogTest extends Specification with TestInjector {

  "AccessLogTimer" should {

    "simple format" in {

      val access = AccessLogTimer(Access.HTTP_OUT)
      //do something
      val line = new AccessLog().format(access.done(remoteHost = "host42", method = "POST"))
      println(line)
      line.contains("type:HTTP_OUT") === true
      line.contains("remoteHost:host42") === true
      line.contains("\tmethod:POST") === true
    }
  }

}
