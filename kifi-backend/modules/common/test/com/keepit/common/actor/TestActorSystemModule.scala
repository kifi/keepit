package com.keepit.common.actor

import akka.actor.{Cancellable, Scheduler, ActorSystem}
import com.google.inject.{Singleton, Provides}
import com.keepit.common.plugin.{SchedulingProperties, SchedulingEnabled}
import com.keepit.inject.AppScoped
import play.api.Play.current

case class TestActorSystemModule(system: Option[ActorSystem] = None) extends ActorSystemModule {

  def configure() {
    bind[ActorBuilder].to[TestActorBuilderImpl]
    bind[Scheduler].to[FakeScheduler]
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
  }

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.Never

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("test-actor-system", current.configuration.underlying, current.classloader))
}

case class StandaloneTestActorSystemModule(system: ActorSystem = ActorSystem("test-actor-system")) extends ActorSystemModule {

  def configure() {
    bind[ActorBuilder].to[TestActorBuilderImpl]
    bind[Scheduler].to[FakeScheduler]
    bind[ActorSystem].toInstance(system)
  }

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.Never

  @Provides @Singleton
  def schedulingProperties: SchedulingProperties = new SchedulingProperties() {
    def allowScheduling = true
  }
}

class FakeScheduler extends Scheduler {
  private def fakeCancellable = new Cancellable() {
    def cancel(): Unit = {}
    def isCancelled = false
  }
  private def immediateExecutor(f: => Unit) = {
    f
    fakeCancellable
  }
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration, interval: scala.concurrent.duration.FiniteDuration, runnable: Runnable)(implicit executor: scala.concurrent.ExecutionContext): Cancellable = fakeCancellable
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration,interval: scala.concurrent.duration.FiniteDuration)(f: => Unit)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = immediateExecutor(f)
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration,interval: scala.concurrent.duration.FiniteDuration,receiver: akka.actor.ActorRef,message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration)(f: => Unit)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = immediateExecutor(f)
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration,receiver: akka.actor.ActorRef,message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration,runnable: Runnable)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
}