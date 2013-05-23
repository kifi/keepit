package com.keepit.search

import java.io.File
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.{Directory, MMapDirectory}
import org.apache.lucene.util.Version
import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.model._
import com.keepit.scraper.{ScraperPluginImpl, ScraperPlugin}
import com.keepit.search.graph.URIGraphFields
import com.keepit.search.graph.URIGraphImpl
import com.keepit.search.graph.{URIGraphPluginImpl, URIGraphPlugin, URIGraph}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.{ArticleIndexerPluginImpl, ArticleIndexerPlugin, ArticleIndexer}
import com.keepit.search.phrasedetector.{PhraseIndexerImpl, PhraseIndexer}
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.shoebox.ShoeboxServiceClient
import com.tzavellas.sse.guice.ScalaModule
import play.api.Play.current
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.RemotePostOffice
import com.keepit.common.mail.RemotePostOfficeImpl
import com.keepit.common.healthcheck.RemoteHealthcheckMailSender
import com.keepit.common.healthcheck.HealthcheckMailSender
import com.keepit.common.net.HttpClient
import com.keepit.shoebox.ShoeboxCacheProvider
import com.keepit.shoebox.ShoeboxServiceClientImpl

class SearchModule() extends ScalaModule with Logging {

  def configure() {
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[RemotePostOffice].to[RemotePostOfficeImpl]
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
    }.get
  }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph, db: Database,
    repo: NormalizedURIRepo, healthcheckPlugin: HealthcheckPlugin, shoeboxClient: ShoeboxServiceClient): ArticleIndexer = {
    val dir = getDirectory(current.configuration.getString("index.article.directory"))
    log.info(s"storing search index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new ArticleIndexer(dir, config, articleStore, db, repo, healthcheckPlugin, shoeboxClient)
  }


  @Singleton
  @Provides
  def uriGraph(bookmarkRepo: BookmarkRepo,
    db: Database, shoeboxClient: ShoeboxServiceClient): URIGraph = {
    val dir = getDirectory(current.configuration.getString("index.urigraph.directory"))
    log.info(s"storing URIGraph in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new URIGraphImpl(dir, config, URIGraphFields.decoders(), bookmarkRepo, db, shoeboxClient)
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
  def spellCorrector: SpellCorrector = {
    val spellDir = getDirectory(current.configuration.getString("index.spell.directory"))
    val articleDir = getDirectory(current.configuration.getString("index.article.directory"))
    SpellCorrector(spellDir, articleDir)
  }
  
  @Singleton
  @Provides
  def clickHistoryBuilder: ClickHistoryBuilder = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get
    
    new ClickHistoryBuilder(filterSize, numHashFuncs, minHits)
  }
  
  @Singleton
  @Provides
  def browsingHistoryBuilder: BrowsingHistoryBuilder = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get
    
    new BrowsingHistoryBuilder(filterSize, numHashFuncs, minHits)
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
        throw new Exception(s"could not create dir $dir")
      }
    }
    ResultClickTracker(dir, numHashFuncs, syncEvery)
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
