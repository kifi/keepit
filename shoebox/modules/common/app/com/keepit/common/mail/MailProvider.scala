package com.keepit.common.mail

import com.google.inject.ImplementedBy

object MailProvider {
  val KIFI_MAIL_ID = "kifi-mail-id"
  val MESSAGE_ID = "Message-ID"
}

@ImplementedBy(classOf[SendgridMailProvider])
trait MailProvider {
  def sendMail(mail: ElectronicMail)
}
