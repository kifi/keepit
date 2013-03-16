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
import com.keepit.shoebox.{ShoeboxGlobal, ShoeboxModule}
import com.keepit.dev.{DevGlobal, DevModule}
import com.keepit.inject._
import com.keepit.model._
import com.keepit.FortyTwoGlobal
import com.tzavellas.sse.guice.ScalaModule
import com.keepit.common.store.FakeStoreModule
import com.keepit.common.healthcheck.{Babysitter, BabysitterImpl, BabysitterTimeout}
import com.keepit.common.db.SlickModule
import com.keepit.common.db.DbInfo
import com.keepit.common.db.slick._
import scala.slick.session.{Database => SlickDatabase}
import org.joda.time.DateTime
import org.joda.time.LocalDate
import akka.actor.{Scheduler, Cancellable, ActorRef}
import akka.util.Duration
import scala.collection.mutable.{Stack => MutableStack}
import com.google.inject.multibindings.Multibinder
import com.keepit.common.analytics.{SliderShownListener, UsefulPageListener, KifiResultClickedListener, EventListenerPlugin}
import com.keepit.common.cache.{HashMapMemoryCache, FortyTwoCachePlugin}
import akka.actor.Scheduler
import akka.actor.Cancellable
import akka.actor.ActorRef
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.concurrent.duration._
import com.keepit.common.actor.ActorPlugin
import akka.actor.ActorSystem

class TestApplication(val _global: TestGlobal) extends play.api.test.FakeApplication() {
  override lazy val global = _global // Play 2.1 makes global a lazy val, which can't be directly overridden.
  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withFakeScheduler() = overrideWith(FakeSchedulerModule())
  def withFakeHttpClient() = overrideWith(FakeHttpClientModule())
  def withFakeStore() = overrideWith(FakeStoreModule())
  def withRealBabysitter() = overrideWith(BabysitterModule())
  def withFakeTime() = overrideWith(FakeTimeModule())
  def withFakeSecureSocialUserService() = overrideWith(FakeSecureSocialUserServiceModule())

  def overrideWith(model: Module): TestApplication =
    new TestApplication(new TestGlobal(Modules.`override`(global.modules: _*).`with`(model)))
}

class DevApplication() extends TestApplication(new TestGlobal(DevGlobal.modules: _*))
class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxGlobal.modules: _*))
class EmptyApplication() extends TestApplication(new TestGlobal(TestModule()))

trait DbRepos {
  import play.api.Play.current
  def db = inject[Database]
  def userRepo = inject[UserRepo]
  def uriRepo = inject[NormalizedURIRepo]
  def urlRepo = inject[URLRepo]
  def bookmarkRepo = inject[BookmarkRepo]
  def socialUserInfoRepo = inject[SocialUserInfoRepo]
  def installationRepo = inject[KifiInstallationRepo]
  def userExperimentRepo = inject[UserExperimentRepo]
  def emailAddressRepo = inject[EmailAddressRepo]
  def unscrapableRepo = inject[UnscrapableRepo]
}

case class TestModule() extends ScalaModule {
  def configure(): Unit = {
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[Babysitter].to[FakeBabysitter]
    install(new SlickModule(new DbInfo() {
      //later on we can customize it by the application name
      lazy val database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
      lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
    }))
    bind[FortyTwoCachePlugin].to[HashMapMemoryCache]

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[KifiResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
  }

  @Provides
  @Singleton
  def clock: Clock = new Clock()

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-test-actor-system")

  @Provides
  @Singleton
  def fortyTwoServices(dateTime: DateTime): FortyTwoServices = FortyTwoServices(dateTime)
}

class FakeClock extends Clock {
  val stack = MutableStack[DateTime]()

  def push(t : DateTime): FakeClock = { stack push t; this }
  def push(d : LocalDate): FakeClock = { stack push d.toDateTimeAtStartOfDay(clockZone); this }

  def currentDate: LocalDate = currentDateTime.toLocalDate
  def currentDateTime: DateTime = if (stack.isEmpty) super.currentDateTime else stack.pop
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
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration, interval: scala.concurrent.duration.FiniteDuration, runnable: Runnable)(implicit executor: scala.concurrent.ExecutionContext): Cancellable = fakeCancellable
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration,interval: scala.concurrent.duration.FiniteDuration)(f: => Unit)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = immediateExecutor(f)
  def schedule(initialDelay: scala.concurrent.duration.FiniteDuration,interval: scala.concurrent.duration.FiniteDuration,receiver: akka.actor.ActorRef,message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration)(f: => Unit)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = immediateExecutor(f)
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration,receiver: akka.actor.ActorRef,message: Any)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
  def scheduleOnce(delay: scala.concurrent.duration.FiniteDuration,runnable: Runnable)(implicit executor: scala.concurrent.ExecutionContext): akka.actor.Cancellable = fakeCancellable
}

