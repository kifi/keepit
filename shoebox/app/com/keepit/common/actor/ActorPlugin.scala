package com.keepit.common.actor

import akka.actor.ActorSystem
import com.google.inject.Provider
import play.api.Play
import com.keepit.common.plugin.SchedulingPlugin

/**
 * Plugin that creates an actor system with classloader configured to be reload-aware.
 *
 * This is implemented as a play Plugin to allow for clean shutdown of the actor system.
 *
 * Also serves as Provider[ActorSystem] so that guice can inject this actor system into
 * other services or plugins that need to use actors.
 */
class ActorPlugin(systemName: String) extends SchedulingPlugin with Provider[ActorSystem] {

  val system = ActorSystem(systemName, Play.current.configuration.underlying, Play.current.classloader)
  def get: ActorSystem = system

  override def enabled: Boolean = true

  override def onStart() {
  }

  override def onStop() {
    system.shutdown()
    system.awaitTermination()
  }
}
