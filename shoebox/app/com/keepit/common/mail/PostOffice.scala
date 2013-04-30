package com.keepit.common.mail

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.logging.Logging

@ImplementedBy(classOf[PostOfficeImpl])
trait PostOffice {
  def sendMail(mail: ElectronicMail)(implicit session: RWSession): ElectronicMail
}

object PostOffice {
  object Categories {
    val HEALTHCHECK = ElectronicMailCategory("HEALTHCHECK")
    val COMMENT = ElectronicMailCategory("COMMENT")
    val MESSAGE = ElectronicMailCategory("MESSAGE")
    val ADMIN = ElectronicMailCategory("ADMIN")
    val EMAIL_KEEP = ElectronicMailCategory("EMAIL_KEEP")
    val INVITATION = ElectronicMailCategory("INVITATION")
  }

  val BODY_MAX_SIZE = 524288
}

class PostOfficeImpl @Inject() (
    mailRepo: ElectronicMailRepo,
    mailer: MailSenderPlugin)
  extends PostOffice with Logging {

  def sendMail(mail: ElectronicMail)(implicit session: RWSession): ElectronicMail = {
    val prepared = {
      val newMail: ElectronicMail = if(mail.htmlBody.value.size > PostOffice.BODY_MAX_SIZE ||
                                      (mail.textBody.isDefined && mail.textBody.get.value.size > PostOffice.BODY_MAX_SIZE)) {
        throw new Exception(s"PostOffice attempted to send an email (${mail.externalId}) longer than ${PostOffice.BODY_MAX_SIZE} bytes. Too big!")
      } else {
        mailRepo.save(mail)
      }
      newMail.prepareToSend()
    }
    mailer.processMail(prepared.id.get)
    prepared
  }
}

