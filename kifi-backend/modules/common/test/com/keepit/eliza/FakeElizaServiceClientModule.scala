package com.keepit.eliza

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import com.keepit.model.{ NormalizedURI, User }
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.actor.FakeScheduler

case class FakeElizaServiceClientModule(attributionInfo: collection.mutable.Map[Id[NormalizedURI], Seq[Id[User]]] = collection.mutable.HashMap.empty) extends ElizaServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def elizaServiceClient(airbrakeNotifier: AirbrakeNotifier): ElizaServiceClient = {
    new FakeElizaServiceClientImpl(airbrakeNotifier, new FakeScheduler(), attributionInfo)
  }

}
