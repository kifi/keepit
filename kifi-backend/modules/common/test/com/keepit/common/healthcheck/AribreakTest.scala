package com.keepit.common.healthcheck

import com.keepit.test._
import com.keepit.common.net._
import com.keepit.common.actor._
import org.specs2.mutable.Specification
import akka.testkit.TestKit

class AirbreakTest extends Specification with TestInjector {

  "AirbreakTest" should {
    "format" in {
      withInjector(StandaloneTestActorSystemModule(), FakeHttpClientModule()) { implicit injector =>
        val actor = inject[ActorInstance[AirbrakeNotifierActor]]
        val notifyer = new AirbrakeNotifier("123", actor)
        val error = AirbrakeError(new IllegalArgumentException("hi there"))
        val xml = notifyer.format(error)
        (xml \ "api-key").head === <api-key>123</api-key>
        (xml \ "error" \ "class").head === <class>java.lang.IllegalArgumentException</class>
        (xml \ "error" \ "message").head === <message>hi there</message>
        (xml \ "error" \ "backtrace" \ "line").head === <line method="apply" file="AribreakTest.scala" number="16"/>
        (xml \ "error" \ "backtrace" \ "line").last === <line method="main" file="ForkMain.java" number="84"/>
        (xml \ "server-environment" \ "environment-name").head === <environment-name>production</environment-name>
      }
    }
  }
}
