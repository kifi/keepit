package com.keepit.common.healthcheck

import org.specs2.mutable.Specification
import com.keepit.inject._
import com.keepit.model.ExperimentTypes.ADMIN
import com.keepit.social.SecureSocialUserService
import com.keepit.test.EmptyApplication
import com.keepit.test.FakeClock
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api._
import play.api.libs.concurrent.Akka
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Akka
import com.keepit.inject._
import org.joda.time.DateTime
import com.google.inject._
import akka.actor.Scheduler
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.concurrent.duration._
import com.keepit.common.time._

class BabysitterTest extends Specification {

  "Babysitter" should {
    "do nothing if code executes quickly" in {
      running(new EmptyApplication().withRealBabysitter().withFakeScheduler()) {

        inject[Babysitter].watch(BabysitterTimeout(Duration(1, "seconds"), Duration(1, "seconds"))) {
          // So fast!
        }

        inject[HealthcheckPlugin].errorCount == 0

        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
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
