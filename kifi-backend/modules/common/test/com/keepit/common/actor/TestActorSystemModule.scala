package com.keepit.common.actor

import akka.actor.{Cancellable, Scheduler, ActorSystem}
import com.google.inject.{Singleton, Provides}
import com.keepit.common.plugin.{SchedulingProperties, SchedulingEnabled}
import play.api.Play
import com.keepit.inject.AppScoped

case class TestActorSystemModule(customSystem: Option[ActorSystem] = None) extends ActorSystemModule {

  val system = customSystem.getOrElse(Play.maybeApplication match {
    case Some(app) => ActorSystem("test-actor-system", app.configuration.underlying, app.classloader)
    case None => ActorSystem()
  })

  override def configure(): Unit = {
    bind[ActorSystem].toInstance(system)
    bind[ActorBuilder].to[TestActorBuilderImpl]
    bind[Scheduler].to[FakeScheduler]
  }

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.Never


  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(system)

  @Singleton
  @Provides
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