package com.keepit.common.mail

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.DBSession.RWSession

trait LocalPostOffice {
  def sendMail(mail: ElectronicMail)(implicit session: RWSession): ElectronicMail
}

class ShoeboxPostOfficeImpl @Inject() (mailRepo: ElectronicMailRepo)
    extends LocalPostOffice with Logging {

  def sendMail(mail: ElectronicMail)(implicit session: RWSession): ElectronicMail = {
    val prepared =
      if (mail.htmlBody.value.size > PostOffice.BODY_MAX_SIZE ||
        mail.textBody.isDefined && mail.textBody.get.value.size > PostOffice.BODY_MAX_SIZE) {
        log.warn(s"PostOffice attempted to send an email (${mail.externalId}) longer than ${PostOffice.BODY_MAX_SIZE} bytes. Too big!")
        val prepMail = mail.copy(
          htmlBody = mail.htmlBody.value.take(PostOffice.BODY_MAX_SIZE - 20) + "<br>\n<br>\n(snip)",
          textBody = mail.textBody.map(_.value.take(PostOffice.BODY_MAX_SIZE - 20) + "\n(snip)")).prepareToSend()
        mailRepo.save(prepMail)
      } else {
        mailRepo.save(mail.prepareToSend())
      }
    prepared
  }
}
