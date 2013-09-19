package com.keepit.common.healthcheck

import com.google.inject.{Singleton, Inject, Provides}
import com.keepit.common.mail.{PostOffice, ElectronicMail}
import com.keepit.common.mail.EmailAddresses.ENG
import scala.collection.mutable.MutableList
import com.keepit.common.service.{FortyTwoServices, ServiceVersion}
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.common.time._

case class FakeAirbrakeModule() extends AirbrakeModule {

  def configure(): Unit = {
    bind[AirbrakeNotifier].to[FakeAirbrakeNotifier]
  }

  lazy val fakeServices = new FortyTwoServices(new SystemClock(), Mode.Test, None, None) {
      override lazy val currentVersion = ServiceVersion("0.0.0")
      override lazy val compilationTime = new SystemClock().now()
      override lazy val baseUrl = "test_kifi.com"
    }

  @Provides
  def formatter(playMode: Mode): AirbrakeFormatter = {
    new AirbrakeFormatter("fakeApiKey", Mode.Test, fakeServices)
  }
}

@Singleton
class FakeAirbrakeNotifier @Inject() (clock: Clock) extends AirbrakeNotifier {
  def notify(error: AirbrakeError): AirbrakeError = error
}
