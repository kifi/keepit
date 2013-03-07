package com.keepit.common.healthcheck

import com.keepit.inject._
import com.keepit.common.mail.FakeOutbox
import com.keepit.test.EmptyApplication
import play.api.Play.current
import play.api.test.Helpers.running
import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxApplication
import com.keepit.shoebox.ShoeboxModule

class HealthcheckErrorTest extends Specification {

  "HealthcheckError" should {
    "create signature" in {
      val errors = for (i <- 1 to 3)
        yield HealthcheckError(error = Some(new IllegalArgumentException("foo error = " + i, new RuntimeException("cause is bar " + i))), callType = Healthcheck.API)
      errors(0).stackTraceHtml === errors(1).stackTraceHtml
      errors(0).stackTraceHtml === errors(2).stackTraceHtml
      errors(0).signature === errors(1).signature
      errors(0).signature === errors(2).signature

      errors(0).titleHtml === "java.lang.IllegalArgumentException: foo error = 1\n<br/> &nbsp; Cause: java.lang.RuntimeException: cause is bar 1\n<br/> &nbsp; Cause: [No Cause]"
    }
  }
}
