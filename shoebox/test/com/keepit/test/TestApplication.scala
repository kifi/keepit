package com.keepit.test

import play.api.Play
import play.api.GlobalSettings
import play.api.Application
import play.api.db.DB
import com.google.inject.Module
import com.google.inject.Singleton
import com.google.inject.Provides
import com.google.inject.util.Modules
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.common.social.FakeSecureSocialUserServiceModule
import com.keepit.shoebox.ShoeboxModule
import com.keepit.dev.DevModule
import com.keepit.inject._
import com.keepit.FortyTwoGlobal
import com.tzavellas.sse.guice.ScalaModule
import com.keepit.common.store.FakeStoreModule
import com.keepit.common.healthcheck.{Babysitter, BabysitterImpl, BabysitterTimeout, HealthcheckPlugin}
import com.keepit.common.db.SlickModule
import com.keepit.common.db.DbInfo
import org.scalaquery.session.Database
import org.joda.time.DateTime
import org.joda.time.LocalDate
import akka.actor.{Scheduler, Cancellable, ActorRef}
import akka.util.Duration
import scala.collection.mutable.{Stack => MutableStack}
import com.google.inject.multibindings.Multibinder
import com.keepit.common.analytics.{UsefulPageListener, KifiResultClickedListener, EventListenerPlugin}

class TestApplication(override val global: TestGlobal) extends play.api.test.FakeApplication() {
  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withFakeScheduler() = overrideWith(FakeSchedulerModule())
  def withFakeHttpClient() = overrideWith(FakeHttpClientModule())
  def withFakeStore() = overrideWith(FakeStoreModule())
  def withRealBabysitter() = overrideWith(BabysitterModule())
  def withFakeTime() = overrideWith(FakeTimeModule())
  def withFakeSecureSocialUserService() = overrideWith(FakeSecureSocialUserServiceModule())

  def overrideWith(model: Module): TestApplication =
    new TestApplication(new TestGlobal(Modules.`override`(global.module).`with`(model)))
}

class DevApplication() extends TestApplication(new TestGlobal(new DevModule()))
class ShoeboxApplication() extends TestApplication(new TestGlobal(new ShoeboxModule()))
class EmptyApplication() extends TestApplication(new TestGlobal(TestModule()))

case class TestModule() extends ScalaModule {
  def configure(): Unit = {
    bind[Babysitter].to[FakeBabysitter]
    install(new SlickModule(new DbInfo() {
      //later on we can customize it by the application name
      lazy val database = Database.forDataSource(DB.getDataSource("shoebox")(Play.current))
      lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
    }))

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[KifiResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
  }

  @Provides @Singleton
  def clock = new FakeClock

  @Provides
  def dateTime(clock: FakeClock) : DateTime = clock.pop

  @Provides
  def localDate(clock: FakeClock) : LocalDate = clock.pop.toLocalDate

  @Provides
  @Singleton
  def fortyTwoServices(dateTime: DateTime): FortyTwoServices = FortyTwoServices(dateTime)
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
  def watch[A](timeout: BabysitterTimeout)(block: => A)(implicit app: Application): A = {
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

  def schedule(initialDelay: Duration,frequency: Duration,receiver: ActorRef,message: Any): Cancellable = fakeCancellable
  def schedule(initialDelay: Duration, frequency: Duration)(f: => Unit): Cancellable = immediateExecutor(f)
  def schedule(initialDelay: Duration, frequency: Duration, runnable: Runnable): Cancellable = fakeCancellable
  def scheduleOnce(delay: Duration, receiver: ActorRef, message: Any): Cancellable = fakeCancellable
  def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable = immediateExecutor(f)
  def scheduleOnce(delay: Duration, runnable: Runnable): Cancellable = fakeCancellable
}

