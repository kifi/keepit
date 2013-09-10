package com.keepit.common.healthcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Provides
import com.keepit.inject.AppScoped
import java.net.InetAddress
import com.keepit.common.actor.ActorInstance
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.net._

trait AirbrakeModule extends ScalaModule

case class ProdAirbrakeModule(apiKey: String) extends AirbrakeModule {

  def configure() {}

  @Provides
  @AppScoped
  def airbrakeProvider(httpClient: HttpClient, actor: ActorInstance[AirbrakeNotifierActor]): AirbrakeNotifier = {
    new AirbrakeNotifierImpl(apiKey, actor)
  }
}
