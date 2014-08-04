package com.keepit.common.healthcheck

import org.specs2.mutable.SpecificationLike

import com.keepit.common.mail._
import com.keepit.test.{ ShoeboxTestInjector }
import com.keepit.common.actor.{ TestKitSupport, FakeActorSystemModule }

class HealthcheckModuleTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  class FakeMailSender extends MailSender(null, null) {
    var mailQueue: List[ElectronicMail] = Nil
    override def sendMail(email: ElectronicMail) = {
      mailQueue = email :: mailQueue
    }
  }

  val fakeMailSender = new FakeMailSender

  val modules = Seq(
    FakeMailModule(),
    FakeActorSystemModule()
  )

  "HealthcheckModule" should {
    "load" in {
      withInjector(modules: _*) { implicit injector =>
        val healthcheck = inject[HealthcheckPlugin]

        val outbox = fakeMailSender.mailQueue
        outbox.size === 0

        healthcheck.errorCount() === 0
        healthcheck.addError(AirbrakeError(new Exception("foo")))
        healthcheck.errorCount() === 1

        healthcheck.resetErrorCount()

        healthcheck.errorCount() === 0

      }
    }
  }
}
