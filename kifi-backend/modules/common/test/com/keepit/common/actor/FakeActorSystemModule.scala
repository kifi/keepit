package com.keepit.common.actor

import akka.actor.{ ActorSystem, Cancellable, Scheduler }
import com.google.inject.Provides
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.plugin.SchedulingProperties
import net.codingwell.scalaguice.ScalaModule

case class FakeActorSystemModule(implicit system: ActorSystem = ActorTestSupport.getActorSystem()) extends ActorSystemModule {

  def configure() {
    install(FakeAirbrakeModule())
    install(FakeSchedulerModule())
    bind[ActorBuilder].to[TestActorBuilderImpl]
    bind[Scheduler].to[FakeScheduler]
    bind[ActorSystem].toInstance(system)
  }
}

case class FakeSchedulerModule() extends ScalaModule {

  def configure() {
    bind[Scheduler].to[FakeScheduler]
  }

  @Provides
  def globalSchedulingEnabled: SchedulingProperties =
    new SchedulingProperties {
      def isRunnerFor(taskName: String) = true
      def enabled = false
      def enabledOnlyForLeader = false
      def enabledOnlyForOneMachine(taskName: String) = false
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
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration, interval: scala.concurrent.duration.FiniteDuration, receiver: akka.actor.ActorRef, message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration, receiver: akka.actor.ActorRef, message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration, runnable: Runnable)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
}
