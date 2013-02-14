package com.keepit.shoebox

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import org.joda.time.{DateTime, LocalDate}

@RunWith(classOf[JUnitRunner])
class ShoeboxModuleTest extends Specification {

  "Module" should {
    "get time" in {
      running(new ShoeboxApplication().withFakeHealthcheck().withFakeMail()) {
        inject[DateTime] !== null
      }
    }
    "get date" in {
      running(new ShoeboxApplication().withFakeHealthcheck().withFakeMail()) {
        inject[LocalDate] !== null
      }
    }
  }
}
