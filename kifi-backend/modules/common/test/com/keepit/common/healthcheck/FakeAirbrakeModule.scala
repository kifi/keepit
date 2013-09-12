package com.keepit.common.healthcheck

import com.google.inject.{Singleton, Inject}
import com.keepit.common.mail.{PostOffice, ElectronicMail}
import com.keepit.common.mail.EmailAddresses.ENG
import scala.collection.mutable.MutableList
import com.keepit.common.service.FortyTwoServices
import play.api.Mode.Mode

case class FakeAirbrakeModule() extends AirbrakeModule {
  def configure(): Unit = {
    bind[AirbrakeNotifier].to[FakeAirbrakeNotifier]
  }
}

@Singleton
class FakeAirbrakeNotifier @Inject() (
    val playMode: Mode,
    val service: FortyTwoServices) extends AirbrakeNotifier {
  val apiKey: String = "fakeApiKey"
  def notifyError(error: AirbrakeError): Unit = {}
}
