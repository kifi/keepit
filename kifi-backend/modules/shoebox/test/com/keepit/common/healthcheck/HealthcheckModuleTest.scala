package com.keepit.common.healthcheck

import org.specs2.mutable.SpecificationLike

import com.keepit.common.mail._
import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.test.Helpers.running
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import com.keepit.common.actor.TestActorSystemModule

class HealthcheckModuleTest extends TestKit(ActorSystem()) with SpecificationLike with ShoeboxApplicationInjector {

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

        val mail1 = healthcheck.reportStart()

        val outbox = fakeMailSender.mailQueue
        outbox.size === 1
        outbox(0).htmlBody === mail1.htmlBody
        mail1.subject.endsWith("started") === true

        val mail2 = healthcheck.reportStop()
        mail2.subject.endsWith("stopped") === true
      }
    }
  }
}
