package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.LargeString
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ LocalPostOffice, ElectronicMailRepo, ElectronicMail }
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

  def send(mailToSend: EmailToSend) = {
    htmlPreProcessor.process(mailToSend) map { result =>
      val toAddresses = Seq(mailToSend.to match {
        case Left(userId) => db.readOnlyReplica { implicit sess => emailAddrRepo.getByUser(userId) }
        case Right(address) => address
      })

      val email = ElectronicMail(
        from = mailToSend.from,
        to = toAddresses,
        cc = mailToSend.cc,
        subject = mailToSend.subject,
        htmlBody = result.htmlBody,
        textBody = result.textBody,
        fromName = mailToSend.fromName,
        category = mailToSend.category
      )

      db.readWrite(attempts = 3) { implicit rw =>
        postOffice.sendMail(email)
      }
    }
  }
}
