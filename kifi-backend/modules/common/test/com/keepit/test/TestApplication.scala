package com.keepit.test

import scala.collection.mutable
import scala.concurrent._
import org.joda.time.{ReadablePeriod, DateTime}
import net.codingwell.scalaguice.{ScalaModule, ScalaMultibinder}
import com.google.inject.Module
import com.google.inject.Provider
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.util.Modules
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.analytics._
import com.keepit.common.cache._
import com.keepit.common.controller.FortyTwoCookies._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck._
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.net.{HttpClient, FakeClientResponse}
import com.keepit.common.service._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.common.zookeeper._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.scraper._
import com.keepit.search._
import com.keepit.shoebox._
import akka.actor.ActorSystem
import play.api.Mode.{Mode, Test}
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import java.io.File
import com.keepit.FortyTwoGlobal
import com.keepit.model.SocialUserInfo
import com.keepit.common.store.{DevStoreModule, ProdStoreModule, FakeS3StoreModule}
import com.keepit.search.SearchConfigModule
import com.keepit.common.social.FakeSecureSocialUserServiceModule
import com.keepit.model.SocialConnection
import com.keepit.classify.FakeDomainTagImporterModule
import scala.Some
import com.keepit.common.healthcheck.BabysitterTimeout
import com.keepit.common.db.SlickModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.service.ServiceVersion
import com.keepit.common.mail.FakeMailModule

class TestApplication(_global: FortyTwoGlobal, useDb: Boolean = true, override val path: File = new File(".")) extends play.api.test.FakeApplication(path = path) {

  private def createTestGlobal(baseGlobal: FortyTwoGlobal, modules: Module*) = if (useDb)
    new TestGlobal(Modules.`override`(baseGlobal.modules: _*).`with`(modules: _*))
  else
    new TestRemoteGlobal(Modules.`override`(baseGlobal.modules: _*).`with`(modules: _*))

  override lazy val global = createTestGlobal(_global, new FakeClockModule()) // Play 2.1 makes global a lazy val, which can't be directly overridden.

  val emptyFakeHttpClient: PartialFunction[String, FakeClientResponse] = Map()

  lazy val devStoreModule = new DevStoreModule(new ProdStoreModule { def configure {} }) { def configure {} }

  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeScraper() = overrideWith(FakeScraperModule())
  def withFakeHttpClient(requestToResponse: PartialFunction[String, FakeClientResponse] = emptyFakeHttpClient) = overrideWith(FakeHttpClientModule(requestToResponse))
  def withFakeStore() = overrideWith(FakeS3StoreModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withRealBabysitter() = overrideWith(BabysitterModule())
  def withFakeSecureSocialUserService() = overrideWith(FakeSecureSocialUserServiceModule())
  def withTestActorSystem(system: ActorSystem) = overrideWith(TestActorSystemModule(Some(system)))
  def withFakePersistEvent() = overrideWith(FakePersistEventModule())
  def withFakeCache() = overrideWith(FakeCacheModule())
  def withS3DevModule() = overrideWith(devStoreModule)
  def withShoeboxServiceModule() = overrideWith(FakeShoeboxServiceModule())
  def withSearchConfigModule() = overrideWith(SearchConfigModule())

  def overrideWith(modules: Module*): TestApplication = new TestApplication(createTestGlobal(global, modules: _*), useDb, path)

}

class EmptyApplication(path: File = new File("./modules/common/")) extends TestApplication(new TestGlobal(TestModule()), path = path)

case class TestModule(dbInfo: Option[DbInfo] = None) extends ScalaModule {
  def configure(): Unit = {
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    bind[Mode].toInstance(Test)
    bind[Babysitter].to[FakeBabysitter]
    install(new SlickModule(dbInfo.getOrElse(TestDbInfo.dbInfo)))
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]
    bind[HealthcheckPlugin].to[FakeHealthcheck]
    bind[SlickSessionProvider].to[TestSlickSessionProvider]
    install(new FakeS3StoreModule())
    install(TestCacheModule())
    install(FakeDiscoveryModule())
    install(TestActorSystemModule())
    install(FakeDomainTagImporterModule())
    install(TestSliderHistoryTrackerModule())
    install(TestShoeboxServiceClientModule())
    install(TestSearchServiceClientModule())
    install(TestMailModule())
    install(FakeHttpClientModule(FakeClientResponse.emptyHttpClient))
    bind[play.api.Application].toProvider(new Provider[play.api.Application] {
      def get(): play.api.Application = current
    }).in(classOf[AppScoped])
  }

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(Some("test.com"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(Some("test.com"))

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
