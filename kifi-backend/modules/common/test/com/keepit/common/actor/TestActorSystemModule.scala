package com.keepit.common.actor

import akka.actor.{Cancellable, Scheduler, ActorSystem}
import com.google.inject.Provides
import com.keepit.common.plugin.{SchedulingPropertiesImpl, SchedulingProperties}
import com.keepit.inject.AppScoped
import play.api.Play.current
import scala.concurrent.future
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.zookeeper.ServiceDiscovery
import net.codingwell.scalaguice.ScalaModule

case class TestActorSystemModule(systemOption: Option[ActorSystem] = None) extends ActorSystemModule {

  lazy val system = systemOption.getOrElse(ActorSystem("test-actor-system", current.configuration.underlying, current.classloader))

  def configure() {
    install(FakeAirbrakeModule())
    install(FakeSchedulerModule())
    bind[ActorBuilder].to[TestActorBuilderImpl]
    bind[Scheduler].to[FakeScheduler]
    bind[ActorSystem].toProvider[ActorPlugin]
  }

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin(system)
}

case class StandaloneTestActorSystemModule(implicit system: ActorSystem) extends ActorSystemModule {

  def configure() {
    bind[ActorBuilder].to[TestActorBuilderImpl]
    bind[ActorSystem].toInstance(system)
    install(FakeSchedulerModule())
  }

}

case class FakeSchedulerModule() extends ScalaModule {

  def configure() {
    bind[Scheduler].to[FakeScheduler]
  }

  @Provides
  def globalSchedulingEnabled: SchedulingProperties =
    new SchedulingProperties {
      def enabled = false
      def enabledOnlyForLeader = false
    }
}

class FakeScheduler extends Scheduler {

  private def fakeCancellable = new Cancellable() {
    def cancel(): Boolean = true
    def isCancelled = false
  }
  def maxFrequency = 1.0D
  private def immediateExecutor(f: => Unit) = {
    f
    fakeCancellable
  }

  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration, interval: scala.concurrent.duration.FiniteDuration, runnable: Runnable)(implicit executor: scala.concurrent.ExecutionContext): Cancellable = fakeCancellable
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration,interval: scala.concurrent.duration.FiniteDuration,receiver: akka.actor.ActorRef,message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration,receiver: akka.actor.ActorRef,message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration,runnable: Runnable)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
}
