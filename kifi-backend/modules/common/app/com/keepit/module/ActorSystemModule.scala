package com.keepit.module

import net.codingwell.scalaguice.ScalaModule
import akka.actor.ActorSystem
import com.keepit.common.actor.ActorPlugin
import com.keepit.inject.AppScoped
import com.google.inject.{Singleton, Provides}
import com.keepit.common.plugin.{SchedulingProperties, SchedulingEnabled}
import play.api.Play
import play.api.Play._

trait ActorSystemModule extends ScalaModule {
  def configure() {
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
  }
}

case class ProdActorSystemModule() extends ActorSystemModule {

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.LeaderOnly

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("shoebox-actor-system",
      Play.current.configuration.underlying,
      Play.current.classloader))

}

case class DevActorSystemModule() extends ActorSystemModule {

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled =
    (current.configuration.getBoolean("scheduler.enabled").map {
      case true => SchedulingEnabled.Never
      case false => SchedulingEnabled.Always
    }).getOrElse(SchedulingEnabled.Never)

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("shoebox-dev-actor-system", Play.current.configuration.underlying, Play.current.classloader))

  @Singleton
  @Provides
  def schedulingProperties: SchedulingProperties = new SchedulingProperties() {
    def allowScheduling = true
  }
}
