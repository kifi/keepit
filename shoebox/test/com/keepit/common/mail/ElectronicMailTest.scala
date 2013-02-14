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
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._


@RunWith(classOf[JUnitRunner])
class ElectronicMailTest extends Specification with TestAkkaSystem {

  "ElectronicMail" should {
    "user filters" in {
      running(new ShoeboxApplication().withFakeHealthcheck().withFakeMail()) {
        val repo = inject[ElectronicMailRepo]
        inject[DBConnection].readWrite { implicit s => 
          repo.save(ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.ENG, subject = "foo 1", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK))
          repo.save(ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.TEAM, subject = "foo 2", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK))
          repo.save(ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.EISHAY, subject = "foo 3", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK))
        }
        inject[DBConnection].readOnly { implicit s =>
          repo.page(0, 10, EmailAddresses.ENG).size == 2
          repo.page(0, 2, EmailAddresses.ENG).size == 2
          repo.count(EmailAddresses.ANDREW) === 3
          repo.count(EmailAddresses.ENG) === 2
        }
      }
    }
  }
}
