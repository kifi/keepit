package com.keepit.common.actor

import net.codingwell.scalaguice.ScalaModule
import akka.actor.{Scheduler, ActorSystem}
import com.keepit.inject.AppScoped
import com.google.inject.{Singleton, Provides}
import com.keepit.common.plugin.{SchedulingProperties, SchedulingEnabled}
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
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.LeaderOnly

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
  def globalSchedulingEnabled: SchedulingEnabled =
    (current.configuration.getBoolean("scheduler.enabled").map {
      case false => SchedulingEnabled.Never
      case true => SchedulingEnabled.Always
    }).getOrElse(SchedulingEnabled.Never)

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("dev-actor-system", Play.current.configuration.underlying, Play.current.classloader))

  @Singleton
  @Provides
  def schedulingProperties: SchedulingProperties = new SchedulingProperties() {
    def allowScheduling = true
    override def neverallowScheduling = false
  }
}
