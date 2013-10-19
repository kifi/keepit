package com.keepit.common.healthcheck

import com.keepit.common.zookeeper.ServiceDiscovery
import com.google.inject.{Singleton, Inject, Provides}
import com.keepit.common.mail.{PostOffice, ElectronicMail}
import com.keepit.common.mail.EmailAddresses.ENG
import scala.collection.mutable.MutableList
import com.keepit.common.service.{FortyTwoServices, ServiceVersion}
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.common.time._
import com.keepit.common.service.FakeServiceModule
import com.keepit.common.zookeeper.FakeDiscoveryModule

case class FakeAirbrakeModule() extends AirbrakeModule {

  def configure(): Unit = {
    install(FakeClockModule())
    install(FakeServiceModule())
    install(FakeDiscoveryModule())
    bind[AirbrakeNotifier].to[FakeAirbrakeNotifier]
  }

  @Provides
  def formatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): AirbrakeFormatter = {
    new AirbrakeFormatter("fakeApiKey", Mode.Test, service, serviceDiscovery)
  }
}

@Singleton
class FakeAirbrakeNotifier @Inject() (clock: Clock) extends AirbrakeNotifier {
  def reportDeployment(): Unit = {}
  def notify(error: AirbrakeError): AirbrakeError = error
}
