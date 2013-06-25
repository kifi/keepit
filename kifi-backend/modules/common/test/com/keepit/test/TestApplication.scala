package com.keepit.test

import scala.collection.mutable
import scala.concurrent._
import scala.slick.session.{Database => SlickDatabase}
import org.apache.zookeeper.CreateMode
import org.joda.time.{ReadablePeriod, DateTime}
import net.codingwell.scalaguice.{ScalaModule, ScalaMultibinder}
import com.google.inject.Module
import com.google.inject.Provider
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.util.Modules
import com.keepit.common.actor.{TestActorBuilderImpl, ActorBuilder, ActorPlugin}
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.analytics._
import com.keepit.common.cache._
import com.keepit.common.controller.FortyTwoCookies._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck._
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.net.{HttpClient, FakeHttpClientModule, FakeClientResponse}
import com.keepit.common.plugin._
import com.keepit.common.service._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.common.zookeeper._
import com.keepit.dev._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.scraper._
import com.keepit.search._
import com.keepit.shoebox._
import com.keepit.social._
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Scheduler
import play.api.Mode.{Mode, Test}
import play.api.Play
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import java.io.File
import com.keepit.FortyTwoGlobal
import com.keepit.common.store.S3ImageStore
import com.keepit.common.amazon._
import com.keepit.common.store.FakeS3StoreModule
import com.keepit.model.SocialConnection
import com.keepit.classify.DomainTagImportSettings
import scala.Some
import com.keepit.common.zookeeper.Node
import com.keepit.common.zookeeper.Path
import com.keepit.common.healthcheck.BabysitterTimeout
import com.keepit.common.service.ServiceVersion
import com.keepit.shoebox.ShoeboxCacheProvider
import com.keepit.common.mail.FakeMailModule
import com.keepit.model.SocialUserInfo
import com.keepit.common.social.FakeSecureSocialUserServiceModule
import com.keepit.model.NormalizedURI
import com.keepit.common.amazon.AmazonInstanceId


class TestApplication(_global: FortyTwoGlobal, useDb: Boolean = true, override val path: File = new File(".")) extends play.api.test.FakeApplication(path = path) {

  private def createTestGlobal(baseGlobal: FortyTwoGlobal, modules: Module*) = if (useDb)
    new TestGlobal(Modules.`override`(baseGlobal.modules: _*).`with`(modules: _*))
  else
    new TestRemoteGlobal(Modules.`override`(baseGlobal.modules: _*).`with`(modules: _*))

  override lazy val global = createTestGlobal(_global, new FakeClockModule()) // Play 2.1 makes global a lazy val, which can't be directly overridden.

  val emptyFakeHttpClient: PartialFunction[String, FakeClientResponse] = Map()

  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeScraper() = overrideWith(FakeScraperModule())
  def withFakeScheduler() = overrideWith(FakeSchedulerModule())
  def withFakeHttpClient(requestToResponse: PartialFunction[String, FakeClientResponse] = emptyFakeHttpClient) = overrideWith(FakeHttpClientModule(requestToResponse))
  def withFakeStore() = overrideWith(FakeS3StoreModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withRealBabysitter() = overrideWith(BabysitterModule())
  def withFakeSecureSocialUserService() = overrideWith(FakeSecureSocialUserServiceModule())
  def withTestActorSystem(system: ActorSystem) = overrideWith(TestActorSystemModule(system))
  def withFakePersistEvent() = overrideWith(FakePersistEventModule())
  def withFakeCache() = overrideWith(FakeCacheModule())
  def withS3DevModule() = overrideWith(new S3DevModule())
  def withShoeboxServiceModule() = overrideWith(ShoeboxServiceModule())
  def withSearchConfigModule() = overrideWith(SearchConfigModule())

  def overrideWith(modules: Module*): TestApplication = new TestApplication(createTestGlobal(global, modules: _*), useDb, path)

}

class EmptyApplication(path: File = new File("./modules/common/")) extends TestApplication(new TestGlobal(TestModule()), path = path)

trait DbRepos {

  import play.api.Play.current

  def db = inject[Database]
  def userSessionRepo = inject[UserSessionRepo]
  def userRepo = inject[UserRepo]
  def basicUserRepo = inject[BasicUserRepo]
  def userConnRepo = inject[UserConnectionRepo]
  def socialConnRepo = inject[SocialConnectionRepo]
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
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]
    bind[HealthcheckPlugin].to[FakeHealthcheck]
    bind[SlickSessionProvider].to[TestSlickSessionProvider]
    install(new FakeS3StoreModule())
    install(new DevCacheModule)
    bind[play.api.Application].toProvider(new Provider[play.api.Application] {
      def get(): play.api.Application = current
    }).in(classOf[AppScoped])
  }

  private def dbInfoFromApplication(): DbInfo = TestDbInfo.dbInfo

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

  @Singleton
  @Provides
  def amazonInstanceInfo: AmazonInstanceInfo =
    new AmazonInstanceInfo(
      instanceId = AmazonInstanceId("i-f168c1a8"),
      localHostname = "ip-10-160-95-26.us-west-1.compute.internal",
      publicHostname = "ec2-50-18-183-73.us-west-1.compute.amazonaws.com",
      localIp = IpAddress("10.160.95.26"),
      publicIp = IpAddress("50.18.183.73"),
      instanceType = "c1.medium",
      availabilityZone = "us-west-1b",
      securityGroups = "default",
      amiId = "ami-1bf9de5e",
      amiLaunchIndex = "0"
    )

  @Provides
  @Singleton
  def fakeZooKeeperClient: ZooKeeperClient = new ZooKeeperClient() {
    private val db = new mutable.HashMap[Node, Array[Byte]]()
    val basePath = Path("")

    def watchNode(node: Node, onDataChanged : Option[Array[Byte]] => Unit) {}
    def watchChildren(path: Path, updateChildren : Seq[Node] => Unit) {}
    def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T) {}
    def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T, notifier: Node => Unit) {}

    def create(path: Path, data: Array[Byte], createMode: CreateMode): Path = path
    def createNode(node: Node, data: Array[Byte], createMode: CreateMode): Node = node
    def createPath(path: Path): Path = path

    def getChildren(path: Path): Seq[Node] = Nil
    def get(node: Node): Array[Byte] = db(node)

    def set(node: Node, data: Array[Byte]) {
      db(node) = data
    }

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
@Singleton
class FakeClock extends Clock with Logging {
  private val stack = mutable.Stack[Long]()
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

case class FakeClockModule() extends ScalaModule {
  def configure() {
    bind[Clock].to[FakeClock]
  }
}

class FakeSocialGraphPlugin extends SocialGraphPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]] =
    future { throw new Exception("Not Implemented") }
  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] =
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
  override def configure(): Unit = {}

  @Singleton
  @Provides
  def fakeShoeboxServiceClient(clickHistoryTracker: ClickHistoryTracker, browsingHistoryTracker: BrowsingHistoryTracker): ShoeboxServiceClient =
    new FakeShoeboxServiceClientImpl(clickHistoryTracker, browsingHistoryTracker)

  @Provides
  @Singleton
  def fakebrowsingHistoryTracker: BrowsingHistoryTracker =
    new FakeBrowsingHistoryTrackerImpl(3067, 2, 1)

  @Provides
  @Singleton
  def fakeclickHistoryTracker: ClickHistoryTracker =
    new FakeClickHistoryTrackerImpl(307, 2, 1)

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

    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)

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

