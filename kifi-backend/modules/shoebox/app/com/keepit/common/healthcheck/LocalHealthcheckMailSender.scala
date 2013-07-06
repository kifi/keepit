package com.keepit.common.healthcheck

import com.keepit.common.mail.{ElectronicMail, LocalPostOffice}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database

class LocalHealthcheckMailSender @Inject() (postOffice: LocalPostOffice, db: Database) extends HealthcheckMailSender {
  def sendMail(email: ElectronicMail) = db.readWrite(postOffice.sendMail(email)(_))
}
