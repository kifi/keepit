package com.keepit.abook

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.actor.FakeScheduler

case class FakeABookServiceClientModule() extends ABookServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def ABookServiceClient(airbrakeNotifier: AirbrakeNotifier): ABookServiceClient = {
    new FakeABookServiceClientImpl(airbrakeNotifier, new FakeScheduler())
  }

  @Singleton
  @Provides
  def abookUploadConf: ABookUploadConf = ABookUploadConf(30, 200)
}
