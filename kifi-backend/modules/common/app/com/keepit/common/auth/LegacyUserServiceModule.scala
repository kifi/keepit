package com.keepit.common.auth

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ RemoteSecureSocialUserPlugin }
import net.codingwell.scalaguice.ScalaModule

trait LegacyUserServiceModule extends ScalaModule

case class ProdRemoteLegacyUserServiceModule() extends LegacyUserServiceModule {
  def configure(): Unit = {}

  @Singleton
  @Provides
  def legacyUserService(
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait): LegacyUserService = {
    new RemoteSecureSocialUserPlugin(airbrake, shoeboxClient, monitoredAwait)
  }
}

case class DevLegacyUserServiceModule() extends LegacyUserServiceModule {
  def configure(): Unit = {}

  @Singleton
  @Provides
  def legacyUserService(
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait): LegacyUserService = {
    new RemoteSecureSocialUserPlugin(airbrake, shoeboxClient, monitoredAwait)
  }
}
