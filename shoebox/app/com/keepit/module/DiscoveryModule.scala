package com.keepit.module

import com.keepit.common.zookeeper._
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices

import play.api.Play
import play.api.Play.current
import play.api.Mode
import play.api.Mode._

import com.tzavellas.sse.guice.ScalaModule

import com.google.inject.{Provider, Inject, Singleton, Provides}

class DiscoveryModule extends ScalaModule with Logging {

  def configure() { }

  @Singleton
  @Provides
  def serviceDiscovery(services: FortyTwoServices, mode: Mode): ServiceDiscovery =
        new ServiceDiscovery {
          def register() = Node("me")
          def isLeader() = true
        }

}
