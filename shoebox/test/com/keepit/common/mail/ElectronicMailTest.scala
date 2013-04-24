package com.keepit.common.mail

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._


class ElectronicMailTest extends Specification {

  "ElectronicMail" should {
    "user filters" in {
      running(new ShoeboxApplication().withFakeMail()) {
        val repo = inject[ElectronicMailRepo]
        inject[Database].readWrite { implicit s =>
          repo.save(ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.ENG, subject = "foo 1", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK))
          repo.save(ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.TEAM, subject = "foo 2", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK))
          repo.save(ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.EISHAY, subject = "foo 3", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK))
        }
        inject[Database].readOnly { implicit s =>
          repo.page(0, 10, EmailAddresses.ENG).size == 2
          repo.page(0, 2, EmailAddresses.ENG).size == 2
          repo.count(EmailAddresses.ANDREW) === 3
          repo.count(EmailAddresses.ENG) === 2
        }
      }
    }
  }
}
