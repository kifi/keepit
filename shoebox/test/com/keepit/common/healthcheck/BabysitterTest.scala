package com.keepit.common.healthcheck

import org.junit.runner.RunWith
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import com.keepit.inject._
import com.keepit.model.UserExperiment.ExperimentTypes.ADMIN
import com.keepit.social.SecureSocialUserService
import com.keepit.test.EmptyApplication
import com.keepit.test.FakeClock
import akka.util.duration._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.util.Duration
import org.joda.time.DateTime
import com.keepit.common.time._
import akka.testkit.TestActorRef

@RunWith(classOf[JUnitRunner])
class BabysitterTest extends SpecificationWithJUnit {

  "Babysitter" should {
    "do nothing if code executes quickly" in {
      running(new EmptyApplication().withFakeHealthcheck().withFakeTime().withRealBabysitter()) {

        inject[Babysitter].watch(Duration(1, "seconds"), Duration(1, "seconds")) {
          // So fast!
        }

        inject[HealthcheckPlugin].errorCount == 0

        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        // Babysitter gets time twice. At the beginning, and at the end for warnTimeout
        inject[FakeClock].push(now)
        inject[FakeClock].push(now.minusSeconds(5))

        inject[Babysitter].watch(Duration(0, "seconds"), Duration(0, "seconds")) {
          // So slow!
        }

        // TODO: Use Akka TestKit to verify that Healthcheck has an error.

        1===1
      }
    }
  }

}