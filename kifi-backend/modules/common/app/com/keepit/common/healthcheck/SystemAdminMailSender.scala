package com.keepit.common.healthcheck

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{AmazonSimpleMailProvider, RemotePostOffice, ElectronicMail}
import play.api.Mode._

@ImplementedBy(classOf[RemoteSystemAdminMailSender])
trait SystemAdminMailSender extends Logging {
  def sendMail(email: ElectronicMail): Unit
}

class RemoteSystemAdminMailSender @Inject() (
    postOffice: RemotePostOffice,
    amazonSimpleMailProvider: AmazonSimpleMailProvider,
    airbreak: AirbrakeNotifier,
    playMode: Mode) extends SystemAdminMailSender {

  def sendMail(email: ElectronicMail): Unit = playMode match {
    case Prod =>
      try {
        amazonSimpleMailProvider.sendMail(email)
      } catch {
        case t: Throwable =>
          airbreak.notify(s"could not send email using amazon mail service, using sendgrid", t)
          postOffice.queueMail(email)
      }
    case _ =>
      log.info(s"skip sending email: $email")
  }
}

