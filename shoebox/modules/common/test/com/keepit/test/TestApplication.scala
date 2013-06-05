package com.keepit.test

import com.keepit.common.actor.{TestActorBuilderImpl, ActorBuilder, ActorPlugin}
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.analytics._
import com.keepit.common.cache.{InMemoryCachePlugin, HashMapMemoryCache, FortyTwoCachePlugin}
import com.keepit.common.controller.FortyTwoCookies._
import com.keepit.common.db._
import com.keepit.common.store.FakeS3StoreModule
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck._
import com.keepit.common.logging.Logging
import com.keepit.common.plugin._
import com.keepit.common.mail._
import com.keepit.common.net.{FakeHttpClientModule,HttpClient}
import com.keepit.common.service._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.common.zookeeper._
import com.keepit.social._
import com.keepit.dev._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.scraper._
import com.keepit.search._
import com.keepit.search.index.FakePhraseIndexerModule
import com.keepit.shoebox._
import com.google.inject.Provider
import com.keepit.shoebox.ClickHistoryTracker
import com.keepit.common.mail.FakeMailModule
import com.keepit.shoebox.BrowsingHistoryTracker
import com.keepit.classify.DomainTagImportSettings
import com.tzavellas.sse.guice.ScalaModule
import org.joda.time.{ReadablePeriod, DateTime}
import org.apache.zookeeper.CreateMode
import scala.collection.mutable.{Stack => MutableStack}
import scala.concurrent._
import scala.slick.session.{Database => SlickDatabase}
import scala.collection.mutable
import com.google.inject.Module
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.google.inject.util.Modules
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Scheduler
import play.api.Mode.{Mode, Test}
import play.api.Play
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.Files
import java.io.File
import play.api.db.DB
import com.keepit.common.controller.{ActionAuthenticator, ShoeboxActionAuthenticator}
import com.keepit.FortyTwoGlobal
import com.keepit.common.store.S3ImageStore

class TestApplication(val _global: FortyTwoGlobal, useDb: Boolean = true, override val path: File = new File(".")) extends play.api.test.FakeApplication(path = path) {
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
  def withShoeboxServiceModule() = overrideWith(ShoeboxServiceModule())
  def withSearchConfigModule() = overrideWith(SearchConfigModule())
  def overrideWith(model: Module): TestApplication =
    if(useDb)
      new TestApplication(new TestGlobal(Modules.`override`(global.modules: _*).`with`(model)), path = path)
    else
      new TestApplication(new TestRemoteGlobal(Modules.`override`(global.modules: _*).`with`(model)), useDb = false, path = path)
}

class DevApplication() extends TestApplication(new TestGlobal(DevGlobal.modules: _*), path = new File("./modules/common/"))
class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxDevGlobal.modules: _*), path = new File("./modules/common/"))
class SearchApplication() extends TestApplication(new TestRemoteGlobal(SearchDevGlobal.modules: _*), useDb = false)
class EmptyApplication() extends TestApplication(new TestGlobal(TestModule()), path = new File("./modules/common/"))

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
  def scrapeInfoRepo = inject[ScrapeInfoRepo]
  def phraseRepo = inject[PhraseRepo]
  def collectionRepo = inject[CollectionRepo]
  def keepToCollectionRepo = inject[KeepToCollectionRepo]
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
    bind[MailToKeepPlugin].to[FakeMailToKeepPlugin]
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]
    bind[HealthcheckPlugin].to[FakeHealthcheck]
    bind[SlickSessionProvider].to[TestSlickSessionProvider]
    bind[ActionAuthenticator].to[ShoeboxActionAuthenticator]
    install(new FakeS3StoreModule())
    install(new FakeCacheModule)
    bind[play.api.Application].toProvider(new Provider[play.api.Application] {
      def get(): play.api.Application = current
    }).in[AppScoped]
  }

  private def dbInfoFromApplication(): DbInfo = TestDbInfo.dbInfo

  @Singleton
  @Provides
  def secureSocialAuthenticatorPlugin(db: Database,
      suiRepo: SocialUserInfoRepo,
      usRepo: UserSessionRepo,
      healthPlugin: HealthcheckPlugin,
      app: play.api.Application): SecureSocialAuthenticatorPlugin = {
    new ShoeboxSecureSocialAuthenticatorPlugin(db, suiRepo, usRepo, healthPlugin, app)
  }

  @Singleton
  @Provides
  def secureSocialUserPlugin(db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    imageStore: S3ImageStore,
    healthcheckPlugin: HealthcheckPlugin): SecureSocialUserPlugin = {
    new ShoeboxSecureSocialUserPlugin(db, socialUserInfoRepo, userRepo, imageStore, healthcheckPlugin)
  }


  @Singleton
  @Provides
  def serviceDiscovery: ServiceDiscovery = new ServiceDiscovery {
    def register() = Node("me")
    def isLeader() = true
  }

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled = SchedulingEnabled.Never

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    DomainTagImportSettings(localDir = "", url = "")
  }

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

  @Provides
  @Singleton
  def localPostOffice(shoeboxPostOfficeImpl: ShoeboxPostOfficeImpl): LocalPostOffice = shoeboxPostOfficeImpl

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(Some("test.com"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(Some("test.com"))

  @Provides
  @Singleton
  def sliderHistoryTracker(sliderHistoryRepo: SliderHistoryRepo, db: Database): SliderHistoryTracker =
    new SliderHistoryTracker(sliderHistoryRepo, db, -1, -1, -1)

  @Singleton
  @Provides
  def shoeboxServiceClient(shoeboxCacheProvided: ShoeboxCacheProvider, httpClient: HttpClient): ShoeboxServiceClient = new ShoeboxServiceClientImpl(null, -1, httpClient,shoeboxCacheProvided)

  @Singleton
  @Provides
  def httpClient(): HttpClient = null

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
    bind[InMemoryCachePlugin].to[HashMapMemoryCache]
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

case class SearchConfigModule() extends ScalaModule {
  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def searchConfigManager(shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait): SearchConfigManager = {
    val optFile = current.configuration.getString("index.config").map(new File(_).getCanonicalFile).filter(_.exists)
    new SearchConfigManager(optFile, shoeboxClient, monitoredAwait)
  }
}

case class ShoeboxServiceModule() extends ScalaModule {
  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def fakeShoeboxServiceClient(
    cacheProvider: ShoeboxCacheProvider,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
    browsingHistoryRepo: BrowsingHistoryRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    clickingHistoryRepo: ClickHistoryRepo,
    normUriRepo: NormalizedURIRepo,
    experimentRepo: SearchConfigExperimentRepo,
    userExperimentRepo: UserExperimentRepo,
    clickHistoryTracker: ClickHistoryTracker,
    browsingHistoryTracker: BrowsingHistoryTracker,
    EventPersisterProvider: Provider[EventPersister], clock: Clock,
    fortyTwoServices: FortyTwoServices
  ): ShoeboxServiceClient = new FakeShoeboxServiceClientImpl(
    cacheProvider,
    db,
    userConnectionRepo,
    userRepo,
    bookmarkRepo,
    browsingHistoryRepo,
    clickingHistoryRepo,
    collectionRepo,
    keepToCollectionRepo,
    normUriRepo,
    experimentRepo,
    userExperimentRepo,
    clickHistoryTracker,
    browsingHistoryTracker,
    clock,
    fortyTwoServices)

  @Provides
  @Singleton
  def browsingHistoryTracker(browsingHistoryRepo: BrowsingHistoryRepo, db: Database): BrowsingHistoryTracker =
    new BrowsingHistoryTracker(3067, 2, 1, browsingHistoryRepo, db)

  @Provides
  @Singleton
  def clickHistoryTracker(repo: ClickHistoryRepo, db: Database): ClickHistoryTracker =
    new ClickHistoryTracker(307, 2, 1, repo, db)

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
    bind[EventPersister].to[FakeEventPersisterImpl]

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListener])

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

