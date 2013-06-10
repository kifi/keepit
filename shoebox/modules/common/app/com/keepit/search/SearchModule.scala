package com.keepit.search

import java.io.File

import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.{Directory, MMapDirectory}
import org.apache.lucene.util.Version

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.RemoteActionAuthenticator
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.mail.RemotePostOffice
import com.keepit.common.mail.RemotePostOfficeImpl
import com.keepit.common.net.HttpClient
import com.keepit.inject._
import com.keepit.search.graph.CollectionIndexer
import com.keepit.search.graph.{URIGraphPluginImpl, URIGraphPlugin, URIGraph, URIGraphIndexer}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.{ArticleIndexerPluginImpl, ArticleIndexerPlugin, ArticleIndexer}
import com.keepit.search.phrasedetector.{PhraseIndexerImpl, PhraseIndexer}
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.shoebox.ShoeboxCacheProvider
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.ShoeboxServiceClientImpl
import com.keepit.social.RemoteSecureSocialAuthenticatorPlugin
import com.keepit.social.RemoteSecureSocialUserPlugin
import com.keepit.social.SecureSocialAuthenticatorPlugin
import com.keepit.social.SecureSocialUserPlugin

import play.api.Play.current

class SearchExclusiveModule() extends ScalaModule with Logging {
  def configure() {
    bind[ActionAuthenticator].to[RemoteActionAuthenticator]
  }

  @Singleton
  @Provides
  def secureSocialAuthenticatorPlugin(
    shoeboxClient: ShoeboxServiceClient,
    healthcheckPlugin: HealthcheckPlugin,
    monitoredAwait: MonitoredAwait,
    app: play.api.Application): SecureSocialAuthenticatorPlugin = {
    new RemoteSecureSocialAuthenticatorPlugin(shoeboxClient, healthcheckPlugin, monitoredAwait, app)
  }

  @Singleton
  @Provides
  def secureSocialUserPlugin(healthcheckPlugin: HealthcheckPlugin,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait): SecureSocialUserPlugin = {
    new RemoteSecureSocialUserPlugin(healthcheckPlugin, shoeboxClient, monitoredAwait)
  }
}


class SearchModule() extends ScalaModule with Logging {

  def configure() {
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
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


  @Singleton
  @Provides
  def searchConfigManager(shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait): SearchConfigManager = {
    val optFile = current.configuration.getString("index.config").map(new File(_).getCanonicalFile).filter(_.exists)
    new SearchConfigManager(optFile, shoeboxClient, monitoredAwait)
  }

}
