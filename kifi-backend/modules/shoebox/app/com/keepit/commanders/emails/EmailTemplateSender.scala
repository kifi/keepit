package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.LargeString
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ LocalPostOffice, ElectronicMailRepo, ElectronicMail, EmailToSend }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.UserEmailAddressRepo

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

@ImplementedBy(classOf[EmailTemplateSenderImpl])
trait EmailTemplateSender {
  def send(emailPartial: EmailToSend): Future[ElectronicMail]
}

class EmailTemplateSenderImpl @Inject() (
    db: Database,
    htmlPreProcessor: EmailTemplateProcessor,
    emailRepo: ElectronicMailRepo,
    postOffice: LocalPostOffice,
    emailAddrRepo: UserEmailAddressRepo,
    config: FortyTwoConfig) extends EmailTemplateSender with Logging {

  def send(sendToSend: EmailToSend) = {
    htmlPreProcessor.process(sendToSend) map { html =>
      val toAddresses = Seq(sendToSend.to match {
        case Left(userId) => db.readOnlyReplica { implicit sess => emailAddrRepo.getByUser(userId) }
        case Right(address) => address
      })

      val email = ElectronicMail(
        from = sendToSend.from,
        to = toAddresses,
        cc = sendToSend.cc,
        subject = sendToSend.subject,
        htmlBody = LargeString(html.body),
        category = sendToSend.category
      )

      db.readWrite(attempts = 3) { implicit rw =>
        postOffice.sendMail(email)
      }
    }
  }
}
