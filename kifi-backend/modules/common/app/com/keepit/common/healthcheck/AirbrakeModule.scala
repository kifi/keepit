package com.keepit.common.healthcheck

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
  @AppScoped
  def airbrakeProvider(httpClient: HttpClient, actor: ActorInstance[AirbrakeNotifierActor], playMode: Mode, service: FortyTwoServices): AirbrakeNotifier = {
    val apiKey = Play.current.configuration.getString("airbrake.key").get
    new AirbrakeNotifierImpl(apiKey, actor, playMode, service)
  }
}

case class DevAirbrakeModule() extends AirbrakeModule {
  def configure() {}

  @Provides
  @AppScoped
  def airbrakeProvider(httpClient: HttpClient, actor: ActorInstance[AirbrakeNotifierActor], mode: Mode, fortyTwoServices: FortyTwoServices): AirbrakeNotifier = {
    new AirbrakeNotifier() {
      val apiKey: String = "fakeApiKey"
      def notifyError(error: AirbrakeError): Unit = println(error)
      val playMode: Mode = mode
      val service: FortyTwoServices = fortyTwoServices
    }
  }
}
