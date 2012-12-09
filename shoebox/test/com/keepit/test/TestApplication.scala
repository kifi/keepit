package com.keepit.test

import play.api.GlobalSettings
import play.api.Application
import com.google.inject.Module
import com.google.inject.Singleton
import com.google.inject.util.Modules
import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.shoebox.ShoeboxModule
import com.keepit.dev.DevModule
import com.keepit.inject._
import com.keepit.FortyTwoGlobal
import com.tzavellas.sse.guice.ScalaModule
import com.keepit.common.store.FakeStoreModule
import org.joda.time.DateTime
import scala.collection.mutable.{Stack => MutableStack}
import com.google.inject.Provides
import org.joda.time.LocalDate
import com.keepit.common.healthcheck.Babysitter
import com.keepit.common.healthcheck.BabysitterImpl
import com.keepit.common.healthcheck.HealthcheckPlugin
import akka.actor.Scheduler
import akka.actor.Cancellable
import akka.actor.ActorRef
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.concurrent.duration._

class TestApplication(val _global: TestGlobal) extends play.api.test.FakeApplication() {
  override lazy val global = _global // Play 2.1 makes global a lazy val, which can't be directly overridden.
  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withFakeScheduler() = overrideWith(FakeSchedulerModule())
  def withFakeHttpClient() = overrideWith(FakeHttpClientModule())
  def withFakeStore() = overrideWith(FakeStoreModule())
  def withRealBabysitter() = overrideWith(BabysitterModule())
  def withFakeTime() = overrideWith(FakeTimeModule())

  def overrideWith(model: Module): TestApplication =
    new TestApplication(new TestGlobal(Modules.`override`(global.module).`with`(model)))
}

class DevApplication() extends TestApplication(new TestGlobal(DevModule()))
class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxModule()))
class EmptyApplication() extends TestApplication(new TestGlobal(TestModule()))

case class TestModule() extends ScalaModule {
  def configure(): Unit = {
    bind[Babysitter].to[FakeBabysitter]
  }

  @Provides @Singleton
  def clock = new FakeClock

  @Provides
  def dateTime(clock: FakeClock) : DateTime = clock.pop

  @Provides
  def localDate(clock: FakeClock) : LocalDate = clock.pop.toLocalDate

}

case class FakeTimeModule() extends ScalaModule {
  def configure(): Unit = {}

  @Provides @Singleton
  def clock = new FakeClock

  @Provides
  def dateTime(clock: FakeClock) : DateTime = clock.pop

  @Provides
  def localDate(clock: FakeClock) : LocalDate = clock.pop.toLocalDate
}


class FakeClock {
  val stack = MutableStack[DateTime]()

  def push(t : DateTime): FakeClock = { stack push t; this }
  def push(d : LocalDate): FakeClock = { stack push d.toDateTimeAtStartOfDay(DEFAULT_DATE_TIME_ZONE); this }
  def pop(): DateTime = if (stack.isEmpty) currentDateTime else stack.pop
}

case class BabysitterModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[Babysitter].to[BabysitterImpl]
  }
}

class FakeBabysitter extends Babysitter {
  def watch[A](warnTimeout: FiniteDuration, errorTimeout: FiniteDuration)(block: => A)(implicit app: Application): A = {
    block
  }
}

case class FakeSchedulerModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[Scheduler].to[FakeScheduler]
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

