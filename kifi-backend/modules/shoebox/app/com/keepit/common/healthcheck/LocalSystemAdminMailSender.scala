package com.keepit.common.healthcheck

import com.keepit.common.mail.{AmazonSimpleMailProvider, ElectronicMail, LocalPostOffice}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database

import play.api.Mode
import play.api.Mode._

class LocalSystemAdminMailSender @Inject() (
    postOffice: LocalPostOffice,
    amazonSimpleMailProvider: AmazonSimpleMailProvider,
    db: Database,
    airbreak: AirbrakeNotifier,
    playMode: Mode) extends SystemAdminMailSender {
  def sendMail(email: ElectronicMail) = playMode match {
    case Prod =>
      try {
        amazonSimpleMailProvider.sendMail(email)
      } catch {
        case t: Throwable =>
          airbreak.notify(s"could not send email using amazon mail service, using sendgrid", t)
          db.readWrite(postOffice.sendMail(email)(_))
      }
    case _ => log.info(s"skip sending email: $email")
  }
}
