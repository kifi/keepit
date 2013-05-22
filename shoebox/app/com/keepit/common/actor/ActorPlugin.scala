package com.keepit.common.actor

import akka.actor.ActorSystem
import com.google.inject.Provider
import play.api.Play
import com.keepit.common.plugin._
import com.keepit.common.logging.Logging

/**
 * Plugin that creates an actor system with classloader configured to be reload-aware.
 *
 * This is implemented as a play Plugin to allow for clean shutdown of the actor system.
 *
 * Also serves as Provider[ActorSystem] so that guice can inject this actor system into
 * other services or plugins that need to use actors.
 */
class ActorPlugin(
    system: ActorSystem,
    val schedulingProperties: SchedulingProperties)
  extends SchedulingPlugin with Provider[ActorSystem] with Logging {

  def get: ActorSystem = system

  override def enabled: Boolean = true

  override def onStart() {
    log.info(s"loaded actor system with:\n$system.config")
  }

  override def onStop() {
    system.shutdown()
    system.awaitTermination()
  }
}
