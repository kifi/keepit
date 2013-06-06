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
  def serviceDiscovery(services: FortyTwoServices, mode: Mode): ServiceDiscovery = mode match {
    case Mode.Prod =>
      //todo: have a dedicated host for zk (instead of using localhost)
      val zk = new ZooKeeperClientImpl("localhost", 2000, Path("/services"),
        Some({zk1 => println(s"in callback, got $zk1")}))
      new ServiceDiscoveryImpl(zk, services)
    case _ =>
      new ServiceDiscovery {
        def register() = Node("me")
        def isLeader() = true
      }
  }

}
