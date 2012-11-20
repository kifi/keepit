package com.keepit.common.mail

import com.keepit.test._
import com.keepit.TestAkkaSystem
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
import com.keepit.common.db.CX


@RunWith(classOf[JUnitRunner])
class PostOfficeTest extends Specification with TestAkkaSystem {

  "PostOffice" should {
    "persist and load email" in {
      running(new ShoeboxApplication().withFakeHealthcheck()) {
        val mail1 = inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.TEAM, subject = "foo 1", htmlBody = "some body in html 1"))
        val outbox1 = CX.withConnection { implicit c => ElectronicMail.outbox() }
        outbox1.size === 1
        outbox1(0).externalId === mail1.externalId
        outbox1(0).htmlBody === mail1.htmlBody
        val mail2 = inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.TEAM, subject = "foo 2", htmlBody = "some body in html 2"))
        val outbox2 = CX.withConnection { implicit c => ElectronicMail.outbox() }
        outbox2.size === 2
        outbox2(1).externalId === mail2.externalId
        outbox2(0).htmlBody === mail1.htmlBody
        outbox2(1).htmlBody === mail2.htmlBody
      }
    }
  }
}
