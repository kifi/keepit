package com.keepit.dev

import akka.actor.ActorSystem
import com.google.common.io.Files
import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.analytics._
import com.keepit.common.cache._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.controllers.core.BookmarkInterner
import com.keepit.inject._
import com.keepit.model.{EmailAddressRepo, UserRepo, PhraseRepo}
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.phrasedetector.PhraseIndexer
import com.keepit.search.{ResultClickTracker, ArticleStore}
import com.mongodb.casbah.MongoConnection
import com.tzavellas.sse.guice.ScalaModule
import java.io.File
import org.apache.lucene.store.{RAMDirectory, MMapDirectory, Directory}
import play.api.Play.current

class ShoeboxDevModule extends ScalaModule with Logging {
  def configure() {}

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    DomainTagImportSettings(localDir = Files.createTempDir().getAbsolutePath, url = "http://localhost:8000/42.zip")
  }

  @AppScoped
  @Provides
  def mailToKeepPlugin(
      system: ActorSystem,
      bookmarkInterner: BookmarkInterner,
      persistEventPlugin: PersistEventPlugin,
      postOffice: PostOffice,
      messageParser: MailToKeepMessageParser): MailToKeepPlugin = {
    for {
      username <- current.configuration.getString("mailtokeep.username")
      password <- current.configuration.getString("mailtokeep.password")
    } yield {
      val server = current.configuration.getString("mailtokeep.server").getOrElse("imap.gmail.com")
      val protocol = current.configuration.getString("mailtokeep.protocol").getOrElse("imaps")
      val emailLabel = System.getProperty("user.name")
      val settings = MailToKeepServerSettings(
        username = username,
        password = password,
        server = server,
        protocol = protocol,
        emailLabel = Some(emailLabel))
      new MailToKeepPluginImpl(system, settings, bookmarkInterner, persistEventPlugin, postOffice, messageParser)
    }
  } getOrElse {
    new FakeMailToKeepPlugin
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
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph): ArticleIndexer = {
    val dir = getDirectory(current.configuration.getString("index.article.directory"))
    log.info(s"storing search index in $dir")
    ArticleIndexer(dir, articleStore)
  }

  @Singleton
  @Provides
  def uriGraph: URIGraph = {
    val dir = getDirectory(current.configuration.getString("index.urigraph.directory"))
    log.info(s"storing URIGraph in $dir")
    URIGraph(dir)
  }

  @Singleton
  @Provides
  def phraseIndexer(db: Database, phraseRepo: PhraseRepo): PhraseIndexer = {
    val dir = getDirectory(current.configuration.getString("index.phrase.directory"))
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    PhraseIndexer(dir, db, phraseRepo)
  }
}

class DevCommonModule extends ScalaModule with Logging {
  def configure() {
    bind[PersistEventPlugin].to[FakePersistEventPluginImpl].in[AppScoped]
    bind[FortyTwoCachePlugin].to[InMemoryCache].in[AppScoped]
  }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    current.configuration.getString("mongo.events.server").map { server =>
      val mongoConn = MongoConnection(server)
      val mongoDB = mongoConn(current.configuration.getString("mongo.events.database").getOrElse("events"))
      new MongoEventStoreImpl(mongoDB)
    }.getOrElse {
      new FakeMongoEventStoreImpl()
    }
  }

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin = new ActorPlugin("shoebox-dev-actor-system")
}

class DevModule extends ScalaModule with Logging {
  def configure() {
    install(new DevCommonModule)
    install(new ShoeboxDevModule)
    install(new SearchDevModule)
  }
}
