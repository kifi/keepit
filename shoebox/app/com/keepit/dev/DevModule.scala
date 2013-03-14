package com.keepit.dev

import java.io.File

import org.apache.lucene.store.{RAMDirectory, MMapDirectory, Directory}

import com.google.common.io.Files
import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.analytics._
import com.keepit.common.cache._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.model.PhraseRepo
import com.keepit.search.ArticleStore
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.phrasedetector.PhraseIndexer
import com.mongodb.casbah.MongoConnection
import com.tzavellas.sse.guice.ScalaModule

import play.api.Play.current

class DevModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    println("configuring DevModule")

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

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    DomainTagImportSettings(localDir = Files.createTempDir().getAbsolutePath, url = "http://localhost:8000/42.zip")
  }


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
