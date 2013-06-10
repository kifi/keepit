package com.keepit.module

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Singleton, Provides}
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.zookeeper._

import play.api.Mode
import play.api.Mode._

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
