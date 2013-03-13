package com.keepit.shoebox

import akka.actor.ActorSystem
import java.net.InetAddress
import org.apache.lucene.store.MMapDirectory
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client
import com.google.common.io.Files
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.keepit.common.analytics.{UsefulPageListener, KifiResultClickedListener, EventListenerPlugin}
import com.keepit.common.cache._
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.db.slick.Database
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckPluginImpl
import com.keepit.common.logging.Logging
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.common.mail.MailSenderPluginImpl
import com.keepit.common.mail.PostOffice
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.common.social.S3SocialUserRawInfoStoreImpl
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialGraphPluginImpl
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.store.S3Bucket
import com.keepit.inject.AppScoped
import com.keepit.inject.FortyTwoModule
import com.keepit.model.{PhraseRepo, SliderHistoryTracker}
import com.keepit.scraper._
import com.keepit.search._
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.graph.URIGraphPluginImpl
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.search.index.ArticleIndexerPluginImpl
import com.keepit.search.phrasedetector.PhraseIndexer
import com.mongodb.casbah.MongoConnection
import com.keepit.search.ArticleStore
import com.keepit.search.S3ArticleSearchResultStoreImpl
import com.keepit.search.S3ArticleStoreImpl
import com.keepit.search.SearchConfigManager
import com.keepit.search.ResultClickTracker
import com.keepit.search.BrowsingHistoryTracker
import com.keepit.search.ClickHistoryTracker
import com.tzavellas.sse.guice.ScalaModule
import java.io.File
import java.net.InetAddress
import org.apache.lucene.store.MMapDirectory
import play.api.Play.current
import com.mongodb.casbah.MongoConnection
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.google.inject.multibindings.Multibinder
import com.keepit.common.analytics.{UsefulPageListener, KifiResultClickedListener, EventListenerPlugin}
import com.keepit.common.cache._
import com.keepit.classify.DomainTagImportSettings
import com.google.common.io.Files
import org.apache.http.HttpHost
import org.apache.http.auth.UsernamePasswordCredentials


class ShoeboxModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    println("configuring ShoeboxModule")
    install(new FortyTwoModule())
    install(new MemcachedCacheModule)
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[PersistEventPlugin].to[PersistEventPluginImpl].in[AppScoped]
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]


    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[KifiResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
  }

  @Singleton
  @Provides
  def articleSearchResultStore(amazonS3Client: AmazonS3): ArticleSearchResultStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.articleSearch.bucket").get)
    new S3ArticleSearchResultStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def articleStore(amazonS3Client: AmazonS3): ArticleStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.article.bucket").get)
    new S3ArticleStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def socialUserRawInfoStore(amazonS3Client: AmazonS3): SocialUserRawInfoStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.social.bucket").get)
    new S3SocialUserRawInfoStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def eventStore(amazonS3Client: AmazonS3): S3EventStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.event.bucket").get)
    new S3EventStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def reportStore(amazonS3Client: AmazonS3): ReportStore = {
    val bucketName = S3Bucket(current.configuration.getString("amazon.s3.report.bucket").get)
    new S3ReportStoreImpl(bucketName, amazonS3Client)
  }

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon.s3").get
    val awsCredentials = new BasicAWSCredentials(
        conf.getString("accessKey").get,
        conf.getString("secretKey").get)
    new AmazonS3Client(awsCredentials)
  }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    val server = current.configuration.getString("mongo.events.server").get
    val mongoConn = MongoConnection(server)
    val mongoDB = mongoConn("events")
    new MongoEventStoreImpl(mongoDB)
  }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph): ArticleIndexer = {
    val dirPath = current.configuration.getString("index.article.directory").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception("could not create dir %s".format(dir))
      }
    }
    log.info("storing search index in %s".format(dir.getAbsolutePath()))
    ArticleIndexer(new MMapDirectory(dir), articleStore)
  }

  @Singleton
  @Provides
  def uriGraph: URIGraph = {
    val dirPath = current.configuration.getString("index.urigraph.directory").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception("could not create dir %s".format(dir))
      }
    }
    log.info("storing URIGraph in %s".format(dir.getAbsolutePath()))
    URIGraph(new MMapDirectory(dir))
  }

  @Singleton
  @Provides
  def phraseIndexer(db: Database, phraseRepo: PhraseRepo): PhraseIndexer = {
    val dirPath = current.configuration.getString("index.phrase.directory").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception("could not create dir %s".format(dir))
      }
    }
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    PhraseIndexer(new MMapDirectory(dir), db, phraseRepo)
  }


  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-actor-system")

  @Provides
  def httpClientProvider: HttpClient = new HttpClientImpl()

  @Provides
  @AppScoped
  def healthcheckProvider(system: ActorSystem, postOffice: PostOffice, services: FortyTwoServices): HealthcheckPlugin = {
    val host = InetAddress.getLocalHost().getCanonicalHostName()
    new HealthcheckPluginImpl(system, host, postOffice, services)
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
      soTimeOut = 30000
    )
  }

  @Singleton
  @Provides
  def searchConfigManager(expRepo: SearchConfigExperimentRepo, db: Database): SearchConfigManager = {
    val optFile = current.configuration.getString("index.config").map(new File(_).getCanonicalFile).filter(_.exists)
    new SearchConfigManager(optFile, expRepo, db)
  }

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
        throw new Exception("could not create dir %s".format(dir))
      }
    }
    ResultClickTracker(dir, numHashFuncs, syncEvery)
  }

  @Singleton
  @Provides
  def clickHistoryTracker: ClickHistoryTracker = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    ClickHistoryTracker(filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def browsingHistoryTracker: BrowsingHistoryTracker = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    BrowsingHistoryTracker(filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def sliderHistoryTracker: SliderHistoryTracker = {
    val conf = current.configuration.getConfig("slider-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    SliderHistoryTracker(filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    // TODO(greg): find a good place to put these files
    val dirPath = Files.createTempDir().getAbsolutePath
    DomainTagImportSettings(localDir = dirPath, url = "http://www.komodia.com/clients/42.zip")
  }
}
