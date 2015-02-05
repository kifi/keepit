package com.keepit.common.actor

import com.keepit.common.util.Configuration
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
    bind[ActorSystem].toProvider[ActorSystemPlugin]
  }

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = system.scheduler

  @Provides
  def globalSchedulingEnabled(serviceDiscovery: ServiceDiscovery): SchedulingProperties =
    new SchedulingPropertiesImpl(serviceDiscovery, !(DiscoveryModule.isCanary)) // can allow some (e.g. heimdal) to run on canary later

  @Provides
  @Singleton
  def actorPluginProvider: ActorSystemPlugin =
    new ActorSystemPlugin(ActorSystem("prod-actor-system",
      Play.current.configuration.underlying,
      Play.current.classloader))

}

case class DevActorSystemModule() extends ActorSystemModule {

  def configure() {
    bind[ActorSystem].toProvider[ActorSystemPlugin]
  }

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = system.scheduler

  @Provides
  def globalSchedulingEnabled(config: Configuration): SchedulingProperties = {
    val enabledProp = config.getBoolean("scheduler.enabled").getOrElse(false)
    new SchedulingProperties {
      def enabled = enabledProp
      def enabledOnlyForLeader = enabledProp
      def enabledOnlyForOneMachine(taskName: String) = enabledProp
    }
  }

  @Singleton
  @Provides
  def actorPluginProvider: ActorSystemPlugin = {
    new ActorSystemPlugin(ActorSystem("dev-actor-system", Play.current.configuration.underlying, Play.current.classloader))
  }
}
