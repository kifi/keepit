package com.keepit.test

import scala.collection.mutable.{Stack => MutableStack}
import scala.concurrent._
import scala.slick.session.{Database => SlickDatabase}
import scala.collection.mutable
import scala.collection.immutable.Set

import org.joda.time.{ReadablePeriod, DateTime}

import com.google.inject.Module
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.google.inject.util.Modules

import com.keepit.common.actor.{TestActorBuilderImpl, ActorBuilder, ActorPlugin}
import com.keepit.common.analytics._
import com.keepit.common.cache.{HashMapMemoryCache, FortyTwoCachePlugin}
import com.keepit.common.controller.FortyTwoCookies._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{FakeMailToKeepPlugin, MailToKeepPlugin, FakeMailModule, MailSenderPlugin, ElectronicMail}
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.service._
import com.keepit.common.social._
import com.keepit.common.store.FakeS3StoreModule
import com.keepit.common.time._
import com.keepit.common.zookeeper._
import com.keepit.dev.{SearchDevGlobal, ShoeboxDevGlobal, DevGlobal, S3DevModule}
import com.keepit.inject._
import com.keepit.model._
import com.keepit.scraper._
import com.keepit.search._
import com.keepit.search.index.FakePhraseIndexerModule

import com.tzavellas.sse.guice.ScalaModule
import org.apache.zookeeper.CreateMode

import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Scheduler

import play.api.Mode.{Mode, Test}
import play.api.Play
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

class TestApplication(val _global: TestGlobal) extends play.api.test.FakeApplication() {
  override lazy val global = _global // Play 2.1 makes global a lazy val, which can't be directly overridden.
  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeScraper() = overrideWith(FakeScraperModule())
  def withFakeScheduler() = overrideWith(FakeSchedulerModule())
  def withFakeHttpClient() = overrideWith(FakeHttpClientModule())
  def withFakeStore() = overrideWith(FakeS3StoreModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withRealBabysitter() = overrideWith(BabysitterModule())
  def withFakeSecureSocialUserService() = overrideWith(FakeSecureSocialUserServiceModule())
  def withFakePhraseIndexer() = overrideWith(FakePhraseIndexerModule())
  def withTestActorSystem(system: ActorSystem) = overrideWith(TestActorSystemModule(system))
  def withFakePersistEvent() = overrideWith(FakePersistEventModule())
  def withFakeCache() = overrideWith(FakeCacheModule())
  def withS3DevModule() = overrideWith(new S3DevModule())

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
  def userSessionRepo = inject[UserSessionRepo]
  def userRepo = inject[UserRepo]
  def userConnRepo = inject[UserConnectionRepo]
  def uriRepo = inject[NormalizedURIRepo]
  def urlRepo = inject[URLRepo]
  def bookmarkRepo = inject[BookmarkRepo]
  def commentRepo = inject[CommentRepo]
  def commentReadRepo = inject[CommentReadRepo]
  def commentRecipientRepo = inject[CommentRecipientRepo]
  def socialUserInfoRepo = inject[SocialUserInfoRepo]
  def installationRepo = inject[KifiInstallationRepo]
  def userExperimentRepo = inject[UserExperimentRepo]
  def emailAddressRepo = inject[EmailAddressRepo]
  def invitationRepo = inject[InvitationRepo]
  def unscrapableRepo = inject[UnscrapableRepo]
  def notificationRepo = inject[UserNotificationRepo]
}

object TestDbInfo {
  val url = "jdbc:h2:mem:shoebox;USER=shoebox;MODE=MYSQL;MVCC=TRUE;DB_CLOSE_DELAY=-1"
  val dbInfo = new DbInfo() {
    //later on we can customize it by the application name
    lazy val database = SlickDatabase.forURL(url = url)
    lazy val driverName = H2.driverName
//    lazy val database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
//    lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
  }
}

case class TestModule(dbInfo: Option[DbInfo] = None) extends ScalaModule {
  def configure(): Unit = {
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    bind[Mode].toInstance(Test)
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[Babysitter].to[FakeBabysitter]
    install(new SlickModule(dbInfo.getOrElse(dbInfoFromApplication)))
    bind[FortyTwoCachePlugin].to[HashMapMemoryCache]
    bind[MailToKeepPlugin].to[FakeMailToKeepPlugin]
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]
    bind[HealthcheckPlugin].to[FakeHealthcheck]
    bind[SlickSessionProvider].to[TestSlickSessionProvider]

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[ResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
  }

  private def dbInfoFromApplication(): DbInfo = TestDbInfo.dbInfo

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.Never

  @Provides
  @Singleton
  def fakeClock: FakeClock = new FakeClock()

  @Provides
  @Singleton
  def fakeZooKeeperClient: ZooKeeperClient = new ZooKeeperClient() {
    val basePath = Path("")

    def watchNode(node: Node, onDataChanged : Option[Array[Byte]] => Unit) {}
    def watchChildren(path: Path, updateChildren : Seq[Node] => Unit) {}
    def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T) {}
    def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T, notifier: Node => Unit) {}

    def create(path: Path, data: Array[Byte], createMode: CreateMode): Path = path
    def createNode(node: Node, data: Array[Byte], createMode: CreateMode): Node = node
    def createPath(path: Path): Path = path

    def getChildren(path: Path): Seq[Node] = Nil
    def get(node: Node): Array[Byte] = ???

    def set(node: Node, data: Array[Byte]) {}

    def delete(path: Path) {}
    def deleteNode(node: Node) {}
    def deleteRecursive(path: Path) {}
  }

  @Provides
  @Singleton
  def mailSenderPlugin: MailSenderPlugin = new MailSenderPlugin {
    def processMail(mailId: ElectronicMail) = throw new Exception("Should not attempt to use mail plugin in test")
    def processOutbox() = throw new Exception("Should not attempt to use mail plugin in test")
  }

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(Some("test.com"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(Some("test.com"))

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
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("shoebox-test-actor-system", Play.current.configuration.underlying, Play.current.classloader))

  @Provides
  @Singleton
  def fortyTwoServices(clock: Clock): FortyTwoServices = new FortyTwoServices(clock, Test, None, None) {
    override lazy val currentVersion: ServiceVersion = ServiceVersion("0.0.0")
    override lazy val compilationTime: DateTime = currentDateTime
  }
}

/**
 * A fake clock allows you to control the time returned by Clocks in tests.
 *
 * If you know how many times the underlying code will call getMillis(), you can push() times onto the stack to have
 * their values returned. You can also completely override the time function by calling setTimeFunction().
 */
class FakeClock extends Clock with Logging {
  private val stack = MutableStack[Long]()
  private var timeFunction: () => Long = () => {
    if (stack.isEmpty) {
      val nowTime = new DateTime(System.currentTimeMillis())
      log.debug(s"FakeClock is retuning real now value: $nowTime")
      nowTime.getMillis
    } else {
      val fakeNowTime = new DateTime(stack.pop())
      log.debug(s"FakeClock is retuning fake now value: $fakeNowTime")
      fakeNowTime.getMillis
    }
  }

  def +=(p: ReadablePeriod) {
    val oldTimeFunction = timeFunction
    timeFunction = { () => new DateTime(oldTimeFunction()).plus(p).getMillis }
  }

  def -=(p: ReadablePeriod) {
    val oldTimeFunction = timeFunction
    timeFunction = { () => new DateTime(oldTimeFunction()).minus(p).getMillis }
  }

  def push(t : DateTime): FakeClock = { stack push t.getMillis; this }
  def setTimeFunction(timeFunction: () => Long) { this.timeFunction = timeFunction }
  override def getMillis(): Long = timeFunction()
}

class FakeSocialGraphPlugin extends SocialGraphPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]] =
    future { throw new Exception("Not Implemented") }
}

case class FakeCacheModule() extends ScalaModule {
  override def configure() {
    bind[FortyTwoCachePlugin].to[HashMapMemoryCache]
  }
}

case class FakeScraperModule() extends ScalaModule {
  override def configure() {
    bind[ScraperPlugin].to[FakeScraperPlugin]
  }
}

class FakeScraperPlugin() extends ScraperPlugin {
  def scrape() = Seq()
  def asyncScrape(uri: NormalizedURI) =
    future { throw new Exception("Not Implemented") }
}

case class TestActorSystemModule(system: ActorSystem) extends ScalaModule {
  override def configure(): Unit = {
    bind[ActorSystem].toInstance(system)
    bind[ActorBuilder].to[TestActorBuilderImpl]
  }
}

case class FakeHealthcheckModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[HealthcheckPlugin].to[FakeHealthcheck]
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
  def watch[A](timeout: BabysitterTimeout)(block: => A): A = {
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

