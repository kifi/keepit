package com.keepit.common.healthcheck

import org.specs2.mutable.Specification
import org.joda.time.DateTime
import scala.concurrent.duration._
import com.keepit.common.time._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.test.CommonTestInjector

class BabysitterTest extends Specification with CommonTestInjector {

  val realBabysitterModule = new ScalaModule() {
    def configure() {
      bind[Babysitter].to[BabysitterImpl]
    }
  }

  "Babysitter" should {
    "do nothing if code executes quickly" in {
      withInjector(realBabysitterModule, FakeAirbrakeModule()) { implicit injector =>
        inject[Babysitter].watch(BabysitterTimeout(Duration(1, "seconds"), Duration(1, "seconds"))) {
          // So fast!
        }

        inject[HealthcheckPlugin].errorCount === 0

        val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        // Babysitter gets time twice. At the beginning, and at the end for warnTimeout
        inject[FakeClock].push(now)
        inject[FakeClock].push(now.minusSeconds(5))

        inject[Babysitter].watch(BabysitterTimeout(Duration(1, "seconds"), Duration(1, "seconds"))) {
          // So slow!
        }

        1 === 1
      }
    }
  }

}
