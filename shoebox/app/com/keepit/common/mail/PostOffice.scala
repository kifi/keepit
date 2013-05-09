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
    val ASANA_HEALTHCHECK = ElectronicMailCategory("ASANA_HEALTHCHECK")
    val COMMENT = ElectronicMailCategory("COMMENT")
    val MESSAGE = ElectronicMailCategory("MESSAGE")
    val ADMIN = ElectronicMailCategory("ADMIN")
    val EMAIL_KEEP = ElectronicMailCategory("EMAIL_KEEP")
    val INVITATION = ElectronicMailCategory("INVITATION")
  }

  val BODY_MAX_SIZE = 1048576
}

class PostOfficeImpl @Inject() (mailRepo: ElectronicMailRepo)
  extends PostOffice with Logging {

  def sendMail(mail: ElectronicMail)(implicit session: RWSession): ElectronicMail = {
    val prepared =
      if (mail.htmlBody.value.size > PostOffice.BODY_MAX_SIZE ||
        mail.textBody.isDefined && mail.textBody.get.value.size > PostOffice.BODY_MAX_SIZE) {
        log.warn(s"PostOffice attempted to send an email (${mail.externalId}) longer than ${PostOffice.BODY_MAX_SIZE} bytes. Too big!")
        mailRepo.save(mail.copy(
          htmlBody = mail.htmlBody.value.take(PostOffice.BODY_MAX_SIZE - 20) + "<br>\n<br>\n(snip)").prepareToSend())
      } else {
        mailRepo.save(mail.prepareToSend())
      }
    prepared
  }
}

