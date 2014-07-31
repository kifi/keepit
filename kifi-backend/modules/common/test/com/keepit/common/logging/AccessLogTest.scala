package com.keepit.common.logging

import com.keepit.test._
import com.keepit.common.time._
import org.specs2.mutable.Specification
import org.joda.time.{ ReadablePeriod, DateTime }

class AccessLogTest extends Specification with CommonTestInjector {

  "AccessLogTimer" should {

    "simple format" in {
      val accessLog = new AccessLog(new FakeClock().
        push(new DateTime(2013, 5, 31, 4, 3, 2, 4, DEFAULT_DATE_TIME_ZONE)).
        push(new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)))
      val access = accessLog.timer(Access.HTTP_OUT)
      //do something
      val line = accessLog.format(access.done(remoteServiceType = "host42", method = "POST"))
      println(line)
      line === "t:2013-05-31 04:03:02.004\ttype:HTTP_OUT\tduration:3\tmethod:POST\tremoteServiceType:host42"
    }
  }

}
