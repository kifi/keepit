package com.keepit.common.healthcheck

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ MailProvider, AmazonSimpleMailProvider, RemotePostOffice, ElectronicMail }
import play.api.Mode._

@ImplementedBy(classOf[RemoteSystemAdminMailSender])
trait SystemAdminMailSender extends Logging {
  def sendMail(email: ElectronicMail): Unit
}

@Singleton
class RemoteSystemAdminMailSender @Inject() (
    postOffice: RemotePostOffice,
    mailProvider: MailProvider,
    airbrake: AirbrakeNotifier,
    playMode: Mode) extends SystemAdminMailSender {

  var notifiedError = false

  def sendMail(email: ElectronicMail): Unit = playMode match {
    case Prod =>
      try {
        mailProvider.sendMail(email)
      } catch {
        case t: Throwable =>
          if (!notifiedError) {
            airbrake.notify(s"could not send email using amazon mail service, using sendgrid", t)
            notifiedError = true
          }
          postOffice.queueMail(email.copy(subject = s"[AWS SES FAIL] ${email.subject}"))
      }
    case _ =>
      log.info(s"skip sending email: $email")
  }
}

