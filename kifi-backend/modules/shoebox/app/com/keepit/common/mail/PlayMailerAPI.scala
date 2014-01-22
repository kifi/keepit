package com.keepit.common.mail

import scala.util.DynamicVariable

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.typesafe.plugin.MailerAPI

class PlayMailerAPI @Inject()(
    db: Database,
    postOffice: LocalPostOffice,
    airbrake: AirbrakeNotifier
    ) extends MailerAPI {
  private val mail = new DynamicVariable(ElectronicMail(
    from = EmailAddresses.NOTIFICATIONS,
    subject = "",
    htmlBody = "",
    category = PostOffice.Categories.System.PLAY))

  private def reportErrors[A](block: => A): A = try block catch {
    case e: Throwable =>
      airbrake.notify(e)
      throw e
  }

  private def notImplemented: MailerAPI = {
    airbrake.notify("This method of the Mailer API is not supported")
    this
  }

  private def send(): Unit = reportErrors {
    db.readWrite { implicit s => postOffice.sendMail(mail.value) }
  }

  def setSubject(subject: String): MailerAPI = reportErrors {
    mail.value = mail.value.copy(subject = subject)
    this
  }
  def setSubject(subject: String, args: AnyRef*): MailerAPI = reportErrors {
    mail.value = mail.value.copy(subject = String.format(subject, args))
    this
  }
  def addFrom(from: String): MailerAPI = reportErrors {
    mail.value = mail.value.copy(from = EmailAddresses(from))
    this
  }
  def addRecipient(recipients: String*): MailerAPI = reportErrors {
    mail.value = mail.value.copy(to = recipients.map { r => new EmailAddressHolder { val address = r } })
    this
  }
  def addCc(ccs: String*): MailerAPI = reportErrors {
    mail.value = mail.value.copy(cc = ccs.map { r => new EmailAddressHolder { val address = r } })
    this
  }
  def addBcc(bccs: String*): MailerAPI = notImplemented
  def setReplyTo(replyTo: String): MailerAPI = notImplemented
  def setCharset(charset: String): MailerAPI = notImplemented
  def addHeader(key: String, value: String): MailerAPI = notImplemented
  def send(bodyText: String): Unit = reportErrors {
    mail.value = mail.value.copy(textBody = Some(bodyText), htmlBody = bodyText)
    send()
  }
  def send(bodyText: String, bodyHtml: String): Unit = reportErrors {
    mail.value = mail.value.copy(textBody = Some(bodyText), htmlBody = bodyHtml)
    send()
  }
  def sendHtml(bodyHtml: String): Unit = reportErrors {
    mail.value = mail.value.copy(htmlBody = bodyHtml)
    send()
  }

}

