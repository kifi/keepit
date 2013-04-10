package com.keepit.test

import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Scheduler
import com.google.inject.Module
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.google.inject.util.Modules
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.analytics.{SliderShownListener, UsefulPageListener, KifiResultClickedListener, EventListenerPlugin}
import com.keepit.common.cache.{HashMapMemoryCache, FortyTwoCachePlugin}
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.db.DbInfo
import com.keepit.common.db.SlickModule
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.healthcheck.{Babysitter, BabysitterImpl, BabysitterTimeout}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{FakeMailToKeepPlugin, MailToKeepPlugin, FakeMailModule}
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSecureSocialUserServiceModule
import com.keepit.common.store.FakeStoreModule
import com.keepit.common.time._
import com.keepit.common.analytics._
import com.keepit.dev.{SearchDevGlobal, ShoeboxDevGlobal, DevGlobal}
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search.index._
import com.keepit.search._
import com.tzavellas.sse.guice.ScalaModule
import org.joda.time.DateTime
import org.joda.time.LocalDate
import play.api.Application
import play.api.Play
import play.api.db.DB
import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import scala.util._
import com.keepit.common.social.SocialGraphPlugin
import scala.collection.mutable.{Stack => MutableStack}
import scala.slick.session.{Database => SlickDatabase}

class TestApplication(val _global: TestGlobal) extends play.api.test.FakeApplication() {
  override lazy val global = _global // Play 2.1 makes global a lazy val, which can't be directly overridden.
  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withFakeScheduler() = overrideWith(FakeSchedulerModule())
  def withFakeHttpClient() = overrideWith(FakeHttpClientModule())
  def withFakeStore() = overrideWith(FakeStoreModule())
  def withRealBabysitter() = overrideWith(BabysitterModule())
  def withFakeSecureSocialUserService() = overrideWith(FakeSecureSocialUserServiceModule())
  def withFakePhraseIndexer() = overrideWith(FakePhraseIndexerModule())
  def withTestActorSystem() = overrideWith(TestActorSystemModule())
  def withFakePersistEvent() = overrideWith(FakePersistEventModule())

  def overrideWith(model: Module): TestApplication =
    new TestApplication(new TestGlobal(Modules.`override`(global.modules: _*).`with`(model)))
}

class DevApplication() extends TestApplication(new TestGlobal(DevGlobal.modules: _*))
class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxDevGlobal.modules: _*))
class SearchApplication() extends TestApplication(new TestGlobal(SearchDevGlobal.modules: _*))
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
    bind[MailToKeepPlugin].to[FakeMailToKeepPlugin]
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[KifiResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
  }

  @Provides
  @Singleton
  def fakeClock: FakeClock = new FakeClock()

  @Provides
  @Singleton
  def clickHistoryTracker(repo: ClickHistoryRepo, db: Database): ClickHistoryTracker = new ClickHistoryTracker(-1, -1, -1, repo, db)

  @Provides
  @Singleton
  def sliderHistoryTracker(sliderHistoryRepo: SliderHistoryRepo, db: Database): SliderHistoryTracker =
    new SliderHistoryTracker(sliderHistoryRepo, db, -1, -1, -1)

  @Provides
  @Singleton
  def browsingHistoryTracker(browsingHistoryRepo: BrowsingHistoryRepo, db: Database): BrowsingHistoryTracker =
    new BrowsingHistoryTracker(-1, -1, -1, browsingHistoryRepo, db)

  @Provides
  @Singleton
  def searchServiceClient: SearchServiceClient = new SearchServiceClientImpl(null, -1, null)

  @Provides
  @Singleton
  def clock(clock: FakeClock): Clock = clock

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-test-actor-system")

  @Provides
  @Singleton
  def fortyTwoServices(clock: Clock): FortyTwoServices = FortyTwoServices(clock)
}

class FakeClock extends Clock with Logging {
  val stack = MutableStack[DateTime]()

  def push(t : DateTime): FakeClock = { stack push t; this }
  def push(d : LocalDate): FakeClock = { stack push d.toDateTimeAtStartOfDay(clockZone); this }

  override def today: LocalDate = this.now.toLocalDate
  override def now: DateTime = {
    if (stack.isEmpty) {
      val nowTime = super.now
      log.debug(s"FakeClock is retuning real now value: $nowTime")
      nowTime
    } else {
      val fakeNowTime = stack.pop
      log.debug(s"FakeClock is retuning fake now value: $fakeNowTime")
      fakeNowTime
    }
  }
}

class FakeSocialGraphPlugin extends SocialGraphPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]] =
    future { throw new Exception("Not Implemented") }
}

case class TestActorSystemModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[ActorSystem].toInstance(ActorSystem("system"))
  }
}

case class FakePersistEventModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[PersistEventPlugin].to[FakePersistEventPluginImpl]
  }
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

