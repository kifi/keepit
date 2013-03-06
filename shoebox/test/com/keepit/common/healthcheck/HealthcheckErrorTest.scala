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
        yield HealthcheckError(error = Some(new IllegalArgumentException("foo error = " + i)), callType = Healthcheck.API)
      errors(0).formattedStackTrace === errors(1).formattedStackTrace
      errors(0).formattedStackTrace === errors(2).formattedStackTrace
      errors(0).signature === errors(1).signature
      errors(0).signature === errors(2).signature
    }
  }
}
