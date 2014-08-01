package com.keepit.common.healthcheck

import org.specs2.mutable.SpecificationLike

import com.keepit.common.mail._
import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.test.Helpers.running
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import com.keepit.common.actor.{TestKitSupport, TestActorSystemModule}

class HealthcheckModuleTest extends TestKitSupport with SpecificationLike with ShoeboxApplicationInjector {

  class FakeMailSender extends MailSender(null, null) {
    var mailQueue: List[ElectronicMail] = Nil
    override def sendMail(email: ElectronicMail) = {
      mailQueue = email :: mailQueue
    }
  }

  val fakeMailSender = new FakeMailSender

  val prodHealthCheckModuleWithLocalSender = new ProdHealthCheckModule {
    override def configure() {
      fakeMailSender.mailQueue = Nil
      bind[MailSender].toInstance(fakeMailSender)
    }
  }

  "HealthcheckModule" should {
    "load" in {
      running(new ShoeboxApplication(FakeMailModule(), prodHealthCheckModuleWithLocalSender, TestActorSystemModule(Some(system)))) {
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
