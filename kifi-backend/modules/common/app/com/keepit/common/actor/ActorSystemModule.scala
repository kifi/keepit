package com.keepit.common.actor

import net.codingwell.scalaguice.ScalaModule
import akka.actor.{Scheduler, ActorSystem}
import com.keepit.inject.AppScoped
import com.google.inject.Provides
import com.keepit.common.plugin.SchedulingProperties
import play.api.Play
import play.api.Play._

trait ActorSystemModule extends ScalaModule

case class ProdActorSystemModule() extends ActorSystemModule {

  def configure() {
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
  }

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = system.scheduler

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("prod-actor-system",
      Play.current.configuration.underlying,
      Play.current.classloader))

}

case class DevActorSystemModule() extends ActorSystemModule {

  def configure() {
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
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

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("dev-actor-system", Play.current.configuration.underlying, Play.current.classloader))
}
