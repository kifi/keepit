package com.keepit.common.healthcheck

import com.keepit.inject._
import com.keepit.common.mail.FakeOutbox
import com.keepit.test.EmptyApplication
import play.api.Play.current
import play.api.test.Helpers.running
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import com.keepit.test.ShoeboxApplication
import com.keepit.shoebox.ShoeboxModule

@RunWith(classOf[JUnitRunner])
class HealthcheckModuleTest extends Specification {

  "HealthcheckModule" should {
    "load" in {
      running(new ShoeboxApplication().withFakeMail()) {
        
        val mail1 = inject[Healthcheck].reportStart()
        val outbox1 = inject[FakeOutbox]
        outbox1.size === 1
        outbox1(0).externalId === mail1.externalId
        mail1.subject.endsWith("started") === true
        
        val mail2 = inject[Healthcheck].reportStop()
        val outbox2 = inject[FakeOutbox]
        outbox2.size === 2
        outbox2(1).externalId === mail2.externalId
        mail2.subject.endsWith("stopped") === true
      } 
    }
  }
}
