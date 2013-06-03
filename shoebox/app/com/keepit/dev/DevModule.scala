package com.keepit.dev

import java.io.File
import com.google.common.io.Files
import com.google.inject.util.Modules
import com.google.inject.{Provides, Singleton, Provider, Inject}
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.plugin._
import com.keepit.common.zookeeper._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.actor.{ActorFactory, ActorPlugin}
import com.keepit.common.analytics._
import com.keepit.common.amazon.{AmazonInstanceInfo, AmazonInstanceId}
import com.keepit.common.cache._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.inject._
import com.keepit.model.{BookmarkRepo, NormalizedURIRepo}
import com.keepit.search.{ArticleStore, ResultClickTracker}
import com.keepit.search.graph.CollectionIndexer
import com.keepit.search.graph.{URIGraph, URIGraphImpl, URIGraphIndexer}
import com.keepit.search.index.{ArticleIndexer, DefaultAnalyzer}
import com.keepit.search.phrasedetector.{PhraseIndexerImpl, PhraseIndexer}
import com.keepit.search.query.parser.{FakeSpellCorrector, SpellCorrector}
import com.mongodb.casbah.MongoConnection
import com.tzavellas.sse.guice.ScalaModule
import play.api.Play.current
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.{Directory, MMapDirectory, RAMDirectory}
import org.apache.lucene.util.Version
import com.keepit.model.CollectionRepo
import com.keepit.model.KeepToCollectionRepo
import com.keepit.model.UserRepo
import com.keepit.common.time.Clock
import com.google.inject.Provider
import akka.actor.ActorSystem
import play.api.Play
import com.keepit.search.SearchServiceClient
import com.keepit.common.service.{FortyTwoServices, IpAddress}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db._
import scala.slick.session.{Database => SlickDatabase}
import play.api.db.DB


class FakeEventPersisterImpl @Inject() (
    system: ActorSystem, eventHelper: EventHelper, val schedulingProperties: SchedulingProperties) extends EventPersister with Logging {
  def persist(event: Event): Unit = {
    eventHelper.newEvent(event)
    log.info("Fake persisting event %s".format(event.externalId))
  }
  def persist(events: Seq[Event]): Unit = {
    log.info("Fake persisting events %s".format(events map (_.externalId) mkString(",")))
  }
}

class ShoeboxDevModule extends ScalaModule with Logging {
  def configure() {
    bind[EventPersister].to[FakeEventPersisterImpl].in[AppScoped]
  }

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled =
    (current.configuration.getBoolean("scheduler.enabled").map {
      case true => SchedulingEnabled.Never
      case false => SchedulingEnabled.Always
    }).getOrElse(SchedulingEnabled.Never)

  @Singleton
  @Provides
  def serviceDiscovery: ServiceDiscovery = new ServiceDiscovery {
    def register() = Node("me")
    def isLeader() = true
  }

  @Singleton
  @Provides
  def schedulingProperties: SchedulingProperties = new SchedulingProperties() {
    def allowScheduling = true
  }

  @Singleton
  @Provides
  def searchUnloadProvider(
    db: Database,
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo,
    persistEventProvider: Provider[EventPersister],
    store: MongoEventStore,
    searchClient: SearchServiceClient,
    clock: Clock,
    fortyTwoServices: FortyTwoServices): SearchUnloadListener = {
    current.configuration.getBoolean("event-listener.searchUnload").getOrElse(false) match {
      case true =>  new SearchUnloadListenerImpl(db,userRepo, normalizedURIRepo, persistEventProvider, store, searchClient, clock, fortyTwoServices)
      case false => new FakeSearchUnloadListenerImpl(userRepo, normalizedURIRepo)
    }
  }

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    DomainTagImportSettings(localDir = Files.createTempDir().getAbsolutePath, url = "http://localhost:8000/42.zip")
  }

  @Singleton
  @Provides
  def amazonInstanceInfo: AmazonInstanceInfo = {
    new AmazonInstanceInfo(null) {
      override lazy val instanceId = AmazonInstanceId("i-f168c1a8")
      override lazy val localHostname = "ip-10-160-95-26.us-west-1.compute.internal"
      override lazy val publicHostname = "ec2-50-18-183-73.us-west-1.compute.amazonaws.com"
      override lazy val localIp = IpAddress("10.160.95.26")
      override lazy val publicIp = IpAddress("50.18.183.73")
      override lazy val instanceType = "c1.medium"
      override lazy val availabilityZone = "us-west-1b"
      override lazy val securityGroups = "default"
      override lazy val amiId = "ami-1bf9de5e"
      override lazy val amiLaunchIndex = "0"
    }
  }

  @Provides
  @Singleton
  def mailToKeepServerSettingsOpt: Option[MailToKeepServerSettings] =
    for {
      username <- current.configuration.getString("mailtokeep.username")
      password <- current.configuration.getString("mailtokeep.password")
    } yield {
      val server = current.configuration.getString("mailtokeep.server").getOrElse("imap.gmail.com")
      val protocol = current.configuration.getString("mailtokeep.protocol").getOrElse("imaps")
      val emailLabel = System.getProperty("user.name")
      MailToKeepServerSettings(
        username = username,
        password = password,
        server = server,
        protocol = protocol,
        emailLabel = Some(emailLabel))
    }

  @Provides
  @Singleton
  def mailToKeepServerSettings: MailToKeepServerSettings = mailToKeepServerSettingsOpt.get

  @AppScoped
  @Provides
  def mailToKeepPlugin(
      actorFactory: ActorFactory[MailToKeepActor],
      mailToKeepServerSettings: Option[MailToKeepServerSettings],
      schedulingProperties: SchedulingProperties): MailToKeepPlugin = {
    mailToKeepServerSettingsOpt match {
      case None => new FakeMailToKeepPlugin(schedulingProperties)
      case _ => new MailToKeepPluginImpl(actorFactory, schedulingProperties)
    }
  }
}

class FakeMailToKeepPlugin @Inject() (val schedulingProperties: SchedulingProperties) extends MailToKeepPlugin with Logging {
  def fetchNewKeeps() {
    log.info("Fake fetching new keeps")
  }
}


class SearchDevModule extends ScalaModule with Logging {
  def configure() {}

  private def getDirectory(maybeDir: Option[String]): Directory = {
    maybeDir.map { d =>
      val dir = new File(d).getCanonicalFile
      if (!dir.exists()) {
        if (!dir.mkdirs()) {
          throw new Exception(s"could not create dir $dir")
        }
      }
      new MMapDirectory(dir)
    }.getOrElse {
      new RAMDirectory()
    }
  }

  @Provides
  @Singleton
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
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph, healthcheckPlugin: HealthcheckPlugin, shoeboxClient: ShoeboxServiceClient): ArticleIndexer = {
    val dir = getDirectory(current.configuration.getString("index.article.directory"))
    log.info(s"storing search index in $dir")

    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new ArticleIndexer(dir, config, articleStore, healthcheckPlugin, shoeboxClient)
  }

  @Singleton
  @Provides
  def uriGraphIndexer(shoeboxClient: ShoeboxServiceClient): URIGraphIndexer = {
    val dir = getDirectory(current.configuration.getString("index.urigraph.directory"))
    log.info(s"storing URIGraph in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new URIGraphIndexer(dir, config, shoeboxClient)
  }

  @Singleton
  @Provides
  def collectionIndexer(shoeboxClient: ShoeboxServiceClient): CollectionIndexer = {
    val dir = getDirectory(current.configuration.getString("index.collection.directory"))
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionIndexer(dir, config, shoeboxClient)
  }

  @Singleton
  @Provides
  def phraseIndexer(shoeboxClient: ShoeboxServiceClient): PhraseIndexer = {
    val dir = getDirectory(current.configuration.getString("index.phrase.directory"))
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
    new PhraseIndexerImpl(dir, config, shoeboxClient)

  }

  @Singleton
  @Provides
  def spellCorrector : SpellCorrector = {
    val spellDir = getDirectory(current.configuration.getString("index.spell.directory"))
    val articleDir = getDirectory(current.configuration.getString("index.article.directory"))

    (spellDir, articleDir) match {
      case (sDir: MMapDirectory, aDir: MMapDirectory) => SpellCorrector(sDir, aDir)
      case _ => new FakeSpellCorrector
    }
  }
}

class DevCommonModule extends ScalaModule with Logging {
  def configure() {
    install(new S3DevModule)
    bind[FortyTwoCachePlugin].to[NoOpCache].in[AppScoped]
    bind[InMemoryCachePlugin].to[EhCacheCache].in[AppScoped]
  }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    current.configuration.getString("mongo.events.server").map { server =>
      val mongoConn = MongoConnection(server)
      val mongoDB = mongoConn(current.configuration.getString("mongo.events.database").getOrElse("events"))
      new MongoS3EventStoreImpl(mongoDB)
    }.getOrElse {
      new FakeMongoS3EventStoreImpl()
    }
  }

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("shoebox-dev-actor-system", Play.current.configuration.underlying, Play.current.classloader))
}

class DevModule extends ScalaModule with Logging {
  def configure() {
    install(new DevCommonModule)
    install(new ShoeboxDevModule)
    install(new SearchDevModule)
  }
}
