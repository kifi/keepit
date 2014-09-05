package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.LargeString
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ LocalPostOffice, ElectronicMailRepo, ElectronicMail, EmailModule }
import com.keepit.inject.FortyTwoConfig

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

@ImplementedBy(classOf[EmailModuleSenderImpl])
trait EmailModuleSender {
  def send(emailPartial: EmailModule): Future[ElectronicMail]
}

class EmailModuleSenderImpl @Inject() (
    db: Database,
    htmlPreProcessor: EmailHtmlPreProcessor,
    emailRepo: ElectronicMailRepo,
    postOffice: LocalPostOffice,
    config: FortyTwoConfig) extends EmailModuleSender with Logging {

  def send(module: EmailModule) = {
    htmlPreProcessor.process(module) map { html =>
      val email = ElectronicMail(
        from = module.from,
        to = module.to,
        cc = module.cc,
        subject = module.subject,
        htmlBody = LargeString(html.body),
        category = module.category
      )

      db.readWrite(attempts = 3) { implicit rw =>
        postOffice.sendMail(email)
      }
    }
  }
}
