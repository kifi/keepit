package com.keepit.module

import java.io.File
import java.net.InetAddress
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.keepit.common.plugin._
import com.keepit.common.zookeeper._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.actor.ActorFactory
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.analytics._
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{HealthcheckHost, HealthcheckPluginImpl, HealthcheckPlugin, HealthcheckActor}
import com.keepit.common.logging.Logging
import com.keepit.common.controller.FortyTwoCookies._
import com.keepit.common.mail.{MailSenderPluginImpl, MailSenderPlugin, PostOffice}
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.inject.{FortyTwoModule, AppScoped}
import com.keepit.model.{UserExperimentRepo, SliderHistoryTracker, SliderHistoryRepo, BrowsingHistoryRepo, ClickHistoryRepo}
import com.keepit.scraper.ScraperConfig
import com.keepit.scraper.{HttpFetcherImpl, HttpFetcher}
import com.keepit.search._
import com.keepit.shoebox.{ShoeboxServiceClientImpl, ShoeboxServiceClient}
import com.mongodb.casbah.MongoConnection
import com.tzavellas.sse.guice.ScalaModule
import akka.actor.ActorSystem
import play.api.Play.current
import com.keepit.model.UserRepo
import com.keepit.model.NormalizedURIRepo
import com.keepit.common.time.Clock
import com.google.inject.Provider
import play.api.Play
import play.api.Mode.Mode
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxCacheProvider
import com.keepit.common.mail.LocalPostOffice

class CommonModule extends ScalaModule with Logging {

  def configure() {
    install(new FortyTwoModule)
    install(new MemcachedCacheModule)
    install(new S3Module)

    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]

    bind[PersistEventPlugin].to[PersistEventPluginImpl].in[AppScoped]

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[ResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
    listenerBinder.addBinding().to(classOf[SearchUnloadListener])
  }

  @Singleton
  @Provides
  def serviceDiscovery: ServiceDiscovery = new ServiceDiscovery {
    def register() = Node("me")
    def isLeader() = true
  }

  @Singleton
  @Provides
  def searchUnloadProvider(
    db: Database,
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo,
    persistEventProvider: Provider[PersistEventPlugin],
    store: MongoEventStore,
    searchClient: SearchServiceClient,
    clock: Clock,
    fortyTwoServices: FortyTwoServices): SearchUnloadListener = {
    new SearchUnloadListenerImpl(db, userRepo, normalizedURIRepo, persistEventProvider, store, searchClient, clock, fortyTwoServices)
  }

  @Singleton
  @Provides
  def searchConfigManager(
      expRepo: SearchConfigExperimentRepo, userExpRepo: UserExperimentRepo, db: Database): SearchConfigManager = {
    val optFile = current.configuration.getString("index.config").map(new File(_).getCanonicalFile).filter(_.exists)
    new SearchConfigManager(optFile, expRepo, userExpRepo, db)
  }

  @Singleton
  @Provides
  def playMode: Mode = current.mode

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(current.configuration.getString("session.domain"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(current.configuration.getString("session.domain"))

  @Singleton
  @Provides
  def resultClickTracker: ResultClickTracker = {
    val conf = current.configuration.getConfig("result-click-tracker").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val syncEvery = conf.getInt("syncEvery").get
    val dirPath = conf.getString("dir").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception(s"could not create dir $dir")
      }
    }
    ResultClickTracker(dir, numHashFuncs, syncEvery)
  }

  @Singleton
  @Provides
  def clickHistoryTracker(repo: ClickHistoryRepo, db: Database, shoeboxClient: ShoeboxServiceClient): ClickHistoryTracker = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new ClickHistoryTracker(filterSize, numHashFuncs, minHits, repo, db, shoeboxClient)
  }

  @Singleton
  @Provides
  def browsingHistoryTracker(browsingHistoryRepo: BrowsingHistoryRepo, db: Database, shoeboxClient: ShoeboxServiceClient): BrowsingHistoryTracker = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new BrowsingHistoryTracker(filterSize, numHashFuncs, minHits, browsingHistoryRepo, db, shoeboxClient)
  }

  @Provides
  @AppScoped
  def actorPluginProvider(schedulingProperties: SchedulingProperties): ActorPlugin =
    new ActorPlugin(ActorSystem("shoebox-actor-system", Play.current.configuration.underlying, Play.current.classloader), schedulingProperties)

  @Provides
  def httpClientProvider(healthcheckPlugin: HealthcheckPlugin): HttpClient = new HttpClientImpl(healthcheckPlugin = healthcheckPlugin)

  @Provides
  @AppScoped
  def healthcheckHost(): HealthcheckHost = HealthcheckHost(InetAddress.getLocalHost().getHostName())

  @Provides
  @AppScoped
  def healthcheckProvider(actorFactory: ActorFactory[HealthcheckActor], db: Database,
      services: FortyTwoServices, host: HealthcheckHost): HealthcheckPlugin = {
    new HealthcheckPluginImpl(actorFactory, services, host, db)
  }

  @Singleton
  @Provides
  def scraperConfig: ScraperConfig = ScraperConfig()

  @Singleton
  @Provides
  def httpFetcher: HttpFetcher = {
    new HttpFetcherImpl(
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 30000,
      soTimeOut = 30000,
      trustBlindly = true
    )
  }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    current.configuration.getString("mongo.events.server").map { server =>
      val mongoConn = MongoConnection(server)
      val mongoDB = mongoConn(current.configuration.getString("mongo.events.database").getOrElse("events"))
      new MongoS3EventStoreImpl(mongoDB)
    }.get
  }

  @Singleton
  @Provides
  def sliderHistoryTracker(sliderHistoryRepo: SliderHistoryRepo, db: Database): SliderHistoryTracker = {
    val conf = current.configuration.getConfig("slider-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new SliderHistoryTracker(sliderHistoryRepo, db, filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def searchServiceClient(client: HttpClient): SearchServiceClient = {
    new SearchServiceClientImpl(
      current.configuration.getString("service.search.host").get,
      current.configuration.getInt("service.search.port").get,
      client)
  }

  @Singleton
  @Provides
  def shoeboxServiceClient (client: HttpClient, cacheProvider: ShoeboxCacheProvider): ShoeboxServiceClient = {
    new ShoeboxServiceClientImpl(
      current.configuration.getString("service.shoebox.host").get,
      current.configuration.getInt("service.shoebox.port").get,
      client, cacheProvider)
  }
}
