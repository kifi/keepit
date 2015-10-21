package com.keepit.common.healthcheck

import com.keepit.common.zookeeper.{ DiscoveryModule, ServiceDiscovery }
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Provides
import com.keepit.inject.AppScoped
import com.keepit.common.actor.ActorInstance
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.net._
import play.api.Play
import play.api.Mode.Mode
import com.keepit.model.User

trait AirbrakeModule extends ScalaModule

case class ProdAirbrakeModule() extends AirbrakeModule {
  def configure() {}

  @Provides
  def formatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): AirbrakeFormatter = {
    val apiKey = Play.current.configuration.getString("airbrake.key").get
    new AirbrakeFormatterImpl(apiKey, playMode, service, serviceDiscovery)
  }

  @Provides
  def jsonFormatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): JsonAirbrakeFormatter = {
    val apiKey = Play.current.configuration.getString("airbrake.key").get
    new JsonAirbrakeFormatter(apiKey, playMode, service, serviceDiscovery)
  }

  @Provides
  def airbrakeProvider(actor: ActorInstance[AirbrakeNotifierActor]): AirbrakeNotifier = {
    new AirbrakeNotifierImpl(actor, DiscoveryModule.isCanary)
  }

}

case class DevAirbrakeModule() extends AirbrakeModule {
  def configure() {}

  @Provides
  def formatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): AirbrakeFormatter = {
    new AirbrakeFormatterImpl("fakeApiKey", playMode, service, serviceDiscovery)
  }

  @Provides
  def jsonFormatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): JsonAirbrakeFormatter = {
    new JsonAirbrakeFormatter("fakeApiKey", playMode, service, serviceDiscovery)
  }

  @Provides
  @AppScoped
  def airbrakeProvider(httpClient: HttpClient, actor: ActorInstance[AirbrakeNotifierActor], mode: Mode, fortyTwoServices: FortyTwoServices): AirbrakeNotifier = {
    new AirbrakeNotifier() {
      def reportDeployment(): Unit = ()
      def notify(error: AirbrakeError): AirbrakeError = { println(error); error }
      val playMode: Mode = mode
      val service: FortyTwoServices = fortyTwoServices
    }
  }
}
