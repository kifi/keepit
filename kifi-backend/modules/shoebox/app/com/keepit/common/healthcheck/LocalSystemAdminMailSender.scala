package com.keepit.common.healthcheck

import com.keepit.common.mail.{ AmazonSimpleMailProvider, ElectronicMail, LocalPostOffice }
import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.Database

import play.api.Mode
import play.api.Mode._

@Singleton
class LocalSystemAdminMailSender @Inject() (
    postOffice: LocalPostOffice,
    amazonSimpleMailProvider: AmazonSimpleMailProvider,
    db: Database,
    airbreak: AirbrakeNotifier,
    playMode: Mode) extends SystemAdminMailSender {

  var notifiedError = false

  def sendMail(email: ElectronicMail) = playMode match {
    case Prod =>
      try {
        amazonSimpleMailProvider.sendMail(email)
      } catch {
        case t: Throwable =>
          if (!notifiedError) {
            airbreak.notify(s"could not send email using amazon mail service, using sendgrid", t)
            notifiedError = true
          }
          db.readWrite(postOffice.sendMail(email.copy(subject = s"[AWS SES FAIL] ${email.subject}"))(_))
      }
    case _ => log.info(s"skip sending email: $email")
  }
}
