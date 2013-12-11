package com.keepit.common.healthcheck

import com.keepit.common.mail.{ElectronicMail, LocalPostOffice}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database

import play.api.Mode
import play.api.Mode._

class LocalHealthcheckMailSender @Inject() (postOffice: LocalPostOffice, db: Database, playMode: Mode) extends HealthcheckMailSender {
  def sendMail(email: ElectronicMail) = playMode match {
    case Prod => db.readWrite(postOffice.sendMail(email)(_))
    case _ => log.info(s"skip sending email: $email")
  }
}
