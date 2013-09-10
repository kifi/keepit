package com.keepit.common.healthcheck

import com.google.inject.Singleton
import com.keepit.common.mail.{PostOffice, ElectronicMail}
import com.keepit.common.mail.EmailAddresses.ENG
import scala.collection.mutable.MutableList


case class FakeAirbrakeModule() extends AirbrakeModule {
  def configure(): Unit = {
    bind[AirbrakeNotifier].to[FakeAirbrakeNotifier]
  }
}

@Singleton
class FakeAirbrakeNotifier extends AirbrakeNotifier {
  val apiKey: String = "fakeApiKey"
  def notifyError(error: AirbrakeError): Unit = {}
}
