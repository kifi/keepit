package com.keepit.common.healthcheck

import com.google.inject.Singleton
import com.keepit.common.mail.{PostOffice, ElectronicMail}
import com.keepit.common.mail.EmailAddresses.ENG
import scala.collection.mutable.MutableList


case class FakeHealthcheckModule() extends HealthCheckModule {
  def configure(): Unit = {
    bind[HealthcheckPlugin].to[FakeHealthcheck]
    bind[Babysitter].to[FakeBabysitter]
  }
}

class FakeBabysitter extends Babysitter {
  def watch[A](timeout: BabysitterTimeout)(block: => A): A = {
    block
  }
}

@Singleton
class FakeHealthcheck extends HealthcheckPlugin {

  val _errors = MutableList[AirbrakeError]()

  def errorCount(): Int = errors.size

  def resetErrorCount(): Unit = _errors.clear

  def errors(): List[AirbrakeError] = _errors.toList

  def reportErrors(): Unit = {}

  def addError(error: AirbrakeError): AirbrakeError = {
    _errors += error
    error
  }

  def reportStart(): ElectronicMail = ElectronicMail(from = ENG, to = List(ENG), subject = "start", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
  def reportStop(): ElectronicMail = ElectronicMail(from = ENG, to = List(ENG), subject = "stop", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
}
