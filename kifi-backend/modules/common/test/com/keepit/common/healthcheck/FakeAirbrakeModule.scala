package com.keepit.common.healthcheck

import com.google.inject.{Singleton, Inject, Provides}
import com.keepit.common.mail.{PostOffice, ElectronicMail}
import com.keepit.common.mail.EmailAddresses.ENG
import scala.collection.mutable.MutableList
import com.keepit.common.service.{FortyTwoServices, ServiceVersion}
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.common.time._
import com.keepit.common.service.FakeServiceModule

case class FakeAirbrakeModule() extends AirbrakeModule {

  def configure(): Unit = {
    install(FakeClockModule())
    install(FakeServiceModule())
    bind[AirbrakeNotifier].to[FakeAirbrakeNotifier]
  }

  @Provides
  def formatter(playMode: Mode, service: FortyTwoServices): AirbrakeFormatter = {
    new AirbrakeFormatter("fakeApiKey", Mode.Test, service)
  }
}

@Singleton
class FakeAirbrakeNotifier @Inject() (clock: Clock) extends AirbrakeNotifier {
  def notify(error: AirbrakeError): AirbrakeError = error
}
