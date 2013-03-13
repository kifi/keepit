package com.keepit.dev

import akka.actor.ActorSystem
import scala.collection.mutable.HashMap
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.store.RAMDirectory
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3._
import com.google.common.io.Files
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings._
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.keepit.common.cache._
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.db.slick.Database
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckPluginImpl
import com.keepit.common.logging.Logging
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.common.mail.MailSenderPluginImpl
import com.keepit.common.mail.PostOffice
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.common.social._
import com.keepit.common.store.S3Bucket
import com.keepit.inject._
import com.keepit.model.{PhraseRepo, SliderHistoryTracker, NormalizedURI, SocialUserInfo}
import com.keepit.scraper._
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search._
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.graph.URIGraphPluginImpl
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.search.index.ArticleIndexerPluginImpl
import com.keepit.search.phrasedetector.PhraseIndexer
import com.keepit.search._
import com.mongodb.casbah.MongoConnection
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.tzavellas.sse.guice.ScalaModule
import java.io.File
import java.net.InetAddress
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.store.RAMDirectory
import play.api.Play.current
import scala.collection.mutable.HashMap
import com.keepit.common.mail.PostOffice
import java.net.InetAddress
import com.keepit.common.analytics.S3EventStoreImpl
import com.keepit.common.analytics.S3EventStore
import com.keepit.common.analytics.Event
import com.keepit.common.analytics.MongoEventStore
import com.keepit.common.analytics.FakeMongoEventStoreImpl
import com.keepit.common.analytics.MongoEventStoreImpl
import com.mongodb.casbah.MongoConnection
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.google.inject.multibindings._
import com.keepit.common.analytics._
import com.keepit.common.cache._
import com.keepit.classify.DomainTagImportSettings
import com.google.common.io.Files
import org.apache.http.HttpHost
import org.apache.http.auth.UsernamePasswordCredentials


class DevModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    install(new FortyTwoModule())
    bind[ActorSystem].toProvider[ActorPlugin].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    bind[SocialGraphRefresher].to[SocialGraphRefresherImpl].in[AppScoped]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[PersistEventPlugin].to[FakePersistEventPluginImpl].in[AppScoped] // if Events need to be persisted in a dev environment, use PersistEventPluginImpl instead
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]
    bind[FortyTwoCachePlugin].to[InMemoryCache].in[AppScoped]
    //install(new MemcachedCacheModule)

    val listenerBinder = Multibinder.newSetBinder(binder(), classOf[EventListenerPlugin])
    listenerBinder.addBinding().to(classOf[KifiResultClickedListener])
    listenerBinder.addBinding().to(classOf[UsefulPageListener])
    listenerBinder.addBinding().to(classOf[SliderShownListener])
  }

  @Singleton
  @Provides
  def amazonS3Client(): AmazonS3 = {
    val conf = current.configuration.getConfig("amazon.s3").get
    val awsCredentials = new BasicAWSCredentials(conf.getString("accessKey").get, conf.getString("secretKey").get)
    println("using awsCredentials: %s -> %s".format(awsCredentials.getAWSAccessKeyId(), awsCredentials.getAWSSecretKey()))
    new AmazonS3Client(awsCredentials)
  }

  @Singleton
  @Provides
  def articleSearchResultStore(client: AmazonS3): ArticleSearchResultStore =
    current.configuration.getString("amazon.s3.articleSearch.bucket") match {
      case None => new HashMap[ExternalId[ArticleSearchResultRef], ArticleSearchResult] with ArticleSearchResultStore
      case Some(bucketName) => new S3ArticleSearchResultStoreImpl(S3Bucket(bucketName), client)
    }

  @Singleton
  @Provides
  def articleStore(client: AmazonS3): ArticleStore =
    current.configuration.getString("amazon.s3.article.bucket") match {
      case None => new HashMap[Id[NormalizedURI], Article] with ArticleStore
      case Some(bucketName) => new S3ArticleStoreImpl(S3Bucket(bucketName), client)
    }


  @Singleton
  @Provides
  def socialUserRawInfoStore(client: AmazonS3): SocialUserRawInfoStore =
    current.configuration.getString("amazon.s3.social.bucket") match {
      case None => new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore
      case Some(bucketName) => new S3SocialUserRawInfoStoreImpl(S3Bucket(bucketName), client)
    }

  @Singleton
  @Provides
  def eventStore(client: AmazonS3): S3EventStore =
    current.configuration.getString("amazon.s3.event.bucket") match {
      case None => new HashMap[ExternalId[Event], Event] with S3EventStore
      case Some(bucketName) => new S3EventStoreImpl(S3Bucket(bucketName), client)
    }

  @Singleton
  @Provides
  def reportStore(client: AmazonS3): ReportStore =
    current.configuration.getString("amazon.s3.report.bucket") match {
      case None => new HashMap[String, CompleteReport] with ReportStore
      case Some(bucketName) => new S3ReportStoreImpl(S3Bucket(bucketName), client)
    }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    current.configuration.getString("mongo.events.server") match {
      case Some(server) =>
        val mongoConn = MongoConnection(server)
        val mongoDB = mongoConn(current.configuration.getString("mongo.events.database").get)
        new MongoEventStoreImpl(mongoDB)
      case None =>
        new FakeMongoEventStoreImpl()
    }
  }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore): ArticleIndexer = {
    val indexDir = current.configuration.getString("index.article.directory") match {
      case None =>
        new RAMDirectory()
      case Some(dirPath) =>
        val dir = new File(dirPath).getCanonicalFile()
        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            throw new Exception("could not create dir %s".format(dir))
          }
        }
        new MMapDirectory(dir)
    }
    ArticleIndexer(indexDir, articleStore)
  }

  @Provides
  def httpClientProvider: HttpClient = new HttpClientImpl()

  @Singleton
  @Provides
  def uriGraph: URIGraph = {
    val indexDir = current.configuration.getString("index.urigraph.directory") match {
      case None =>
        new RAMDirectory()
      case Some(dirPath) =>
        val dir = new File(dirPath).getCanonicalFile()
        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            throw new Exception("could not create dir %s".format(dir))
          }
        }
        new MMapDirectory(dir)
    }
    URIGraph(indexDir)
  }

  @Singleton
  @Provides
  def phraseIndexer(db: Database, phraseRepo: PhraseRepo): PhraseIndexer = {
    val indexDir = current.configuration.getString("index.phrase.directory") match {
      case None =>
        new RAMDirectory()
      case Some(dirPath) =>
        val dir = new File(dirPath).getCanonicalFile()
        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            throw new Exception("could not create dir %s".format(dir))
          }
        }
        new MMapDirectory(dir)
    }
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }

    PhraseIndexer(indexDir, db, phraseRepo)
  }

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-dev-actor-system")

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

    conf.getString("dir") match {
      case None => ResultClickTracker(numHashFuncs)
      case Some(dirPath) =>
        val dir = new File(dirPath).getCanonicalFile()
        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            throw new Exception("could not create dir %s".format(dir))
          }
        }
        ResultClickTracker(dir, numHashFuncs, syncEvery)
    }
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
    DomainTagImportSettings(localDir = Files.createTempDir().getAbsolutePath, url = "http://localhost:8000/42.zip")
  }

}
