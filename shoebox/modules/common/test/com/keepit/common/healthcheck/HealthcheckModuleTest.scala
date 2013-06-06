package com.keepit.common.healthcheck

import org.specs2.mutable.Specification

import com.keepit.common.mail.FakeOutbox
import com.keepit.inject._
import com.keepit.test.ShoeboxApplication

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.Play.current
import play.api.test.Helpers.running

class HealthcheckModuleTest extends TestKit(ActorSystem()) with Specification {
  "HealthcheckModule" should {
    "load" in {
      running(new ShoeboxApplication().withFakeMail().withFakeCache().withTestActorSystem(system)) {

        val mail1 = inject[HealthcheckPlugin].reportStart()

        val outbox1 = inject[FakeOutbox]
        outbox1.size === 1
        outbox1(0).htmlBody === mail1.htmlBody
        mail1.subject.endsWith("started") === true

        val mail2 = inject[HealthcheckPlugin].reportStop()
        val outbox2 = inject[FakeOutbox]
        outbox2.size === 2
        outbox2(1).htmlBody === mail2.htmlBody
        mail2.subject.endsWith("stopped") === true
      }
    }
  }
}
