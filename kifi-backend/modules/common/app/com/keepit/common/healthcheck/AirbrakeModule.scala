package com.keepit.common.healthcheck

import com.keepit.common.zookeeper.ServiceDiscovery
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Provides
import com.keepit.inject.AppScoped
import java.net.InetAddress
import com.keepit.common.actor.ActorInstance
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.net._
import play.api.Play
import play.api.Mode.Mode

trait AirbrakeModule extends ScalaModule

case class ProdAirbrakeModule() extends AirbrakeModule {
  def configure() {}

  @Provides
  def formatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): AirbrakeFormatter = {
    val apiKey = Play.current.configuration.getString("airbrake.key").get
    new AirbrakeFormatter(apiKey, playMode, service, serviceDiscovery)
  }

  @Provides
  def airbrakeProvider(actor: ActorInstance[AirbrakeNotifierActor]): AirbrakeNotifier = {
    new AirbrakeNotifierImpl(actor)
  }

}

case class DevAirbrakeModule() extends AirbrakeModule {
  def configure() {}

  @Provides
  def formatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): AirbrakeFormatter = {
    new AirbrakeFormatter("fakeApiKey", playMode, service, serviceDiscovery)
  }

  @Provides
  @AppScoped
  def airbrakeProvider(httpClient: HttpClient, actor: ActorInstance[AirbrakeNotifierActor], mode: Mode, fortyTwoServices: FortyTwoServices): AirbrakeNotifier = {
    new AirbrakeNotifier() {
      def reportDeployment(): Unit = ()
      def notify(error: AirbrakeError): AirbrakeError = {println(error); error}
      val playMode: Mode = mode
      val service: FortyTwoServices = fortyTwoServices
    }
  }
}
