package com.keepit.common.healthcheck

import com.keepit.inject._
import com.keepit.common.mail.FakeOutbox
import com.keepit.test.EmptyApplication
import play.api.Play.current
import play.api.test.Helpers.running
import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxApplication
import com.keepit.shoebox.ShoeboxModule
import akka.testkit.TestKit
import akka.actor.ActorSystem

class HealthcheckModuleTest extends TestKit(ActorSystem()) with Specification {
  args(skipAll = true)
  
  "HealthcheckModule" should {
    "load" in {
      running(new ShoeboxApplication().withFakeMail().withFakeCache().withTestActorSystem(system)) {

        val mail1 = inject[HealthcheckPlugin].reportStart()
        
        val outbox1 = inject[FakeOutbox]
        outbox1.size === 1
        outbox1(0).id.get === mail1.id.get
        mail1.subject.endsWith("started") === true

        val mail2 = inject[HealthcheckPlugin].reportStop()
        val outbox2 = inject[FakeOutbox]
        outbox2.size === 2
        outbox2(1).id.get === mail2.id.get
        mail2.subject.endsWith("stopped") === true
      }
    }
  }
}
