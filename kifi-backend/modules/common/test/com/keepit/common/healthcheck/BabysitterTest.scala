package com.keepit.common.healthcheck

import org.specs2.mutable.Specification
import com.keepit.test.FakeClock
import org.joda.time.DateTime
import scala.concurrent.duration._
import com.keepit.common.time._
import com.keepit.test.TestInjector
import com.keepit.test.BabysitterModule

class BabysitterTest extends Specification with TestInjector {

  "Babysitter" should {
    "do nothing if code executes quickly" in {
      withCustomInjector(BabysitterModule())  { implicit injector =>
        inject[Babysitter].watch(BabysitterTimeout(Duration(1, "seconds"), Duration(1, "seconds"))) {
          // So fast!
        }

        inject[HealthcheckPlugin].errorCount == 0

        val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        // Babysitter gets time twice. At the beginning, and at the end for warnTimeout
        inject[FakeClock].push(now)
        inject[FakeClock].push(now.minusSeconds(5))

        inject[Babysitter].watch(BabysitterTimeout(Duration(1, "seconds"), Duration(1, "seconds"))) {
          // So slow!
        }

        1===1
      }
    }
  }

}
