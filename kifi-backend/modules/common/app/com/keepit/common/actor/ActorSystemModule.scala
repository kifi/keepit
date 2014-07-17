package com.keepit.common.actor

import net.codingwell.scalaguice.ScalaModule
import akka.actor.{ Scheduler, ActorSystem }
import com.keepit.inject.AppScoped
import com.google.inject.{ Singleton, Provides }
import play.api.Play
import play.api.Play._
import com.keepit.common.zookeeper.{ DiscoveryModule, ServiceDiscovery }
import com.keepit.common.plugin.{ SchedulingPropertiesImpl, SchedulingProperties }

trait ActorSystemModule extends ScalaModule

case class ProdActorSystemModule() extends ActorSystemModule {

  def configure() {
    bind[ActorSystem].toProvider[ActorPlugin]
  }

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = system.scheduler

  @Provides
  def globalSchedulingEnabled(serviceDiscovery: ServiceDiscovery): SchedulingProperties =
    new SchedulingPropertiesImpl(serviceDiscovery, !(DiscoveryModule.isCanary)) // can allow some (e.g. heimdal) to run on canary later

  @Provides
  @Singleton
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("prod-actor-system",
      Play.current.configuration.underlying,
      Play.current.classloader))

}

case class DevActorSystemModule() extends ActorSystemModule {

  def configure() {
    bind[ActorSystem].toProvider[ActorPlugin]
  }

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = system.scheduler

  @Provides
  def globalSchedulingEnabled: SchedulingProperties = {
    val enabledProp = current.configuration.getBoolean("scheduler.enabled").getOrElse(false)
    new SchedulingProperties {
      def enabled = enabledProp
      def enabledOnlyForLeader = enabledProp
    }
  }

  @Singleton
  @Provides
  def actorPluginProvider: ActorPlugin = {
    new ActorPlugin(ActorSystem("dev-actor-system", Play.current.configuration.underlying, Play.current.classloader))
  }
}
