package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.search.graph._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index._
import play.api.Play._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.message.{MessageIndexer, MessageIndexerPlugin, MessageIndexerPluginImpl}
import com.keepit.search.phrasedetector.{PhraseIndexerPluginImpl, PhraseIndexerPlugin, PhraseIndexerImpl, PhraseIndexer}
import com.keepit.search.spellcheck.{SpellCorrector, SpellIndexerPlugin, SpellIndexerPluginImpl, SpellIndexer}
import com.keepit.inject.AppScoped
import java.io.File
import com.keepit.common.logging.Logging
import com.keepit.search.user.UserIndexer
import com.keepit.search.user.UserIndexerPlugin
import com.keepit.search.user.UserIndexerPluginImpl
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import org.apache.commons.io.FileUtils
import com.keepit.search.article.ArticleIndexerPluginImpl
import com.keepit.search.article.ArticleIndexerPlugin
import com.keepit.search.article.ArticleIndexer

trait IndexModule extends ScalaModule with Logging {

  protected def getPersistentIndexDirectory(maybeDir: Option[String], indexStore: IndexStore): Option[IndexDirectory] = {
    maybeDir.map { d =>
      val dir = new File(d).getCanonicalFile
      val indexDirectory = new IndexDirectoryImpl(dir, indexStore)
      if (!dir.exists()) {
        try {
          val t1 = currentDateTime.getMillis
          indexDirectory.restoreFromBackup()
          val t2 = currentDateTime.getMillis
          log.info(s"$d was restored from S3 in ${(t2 - t1) / 1000} seconds")
        }
        catch { case e: Exception => {
          log.error(s"Could not restore $dir from S3}", e)
          FileUtils.deleteDirectory(dir)
          FileUtils.forceMkdir(dir)
        }}
      }
      indexDirectory
    }
  }

  protected def getIndexDirectory(dir: String, indexStore: IndexStore): IndexDirectory

  def configure() {
    bind[PhraseIndexerPlugin].to[PhraseIndexerPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[MessageIndexerPlugin].to[MessageIndexerPluginImpl].in[AppScoped]
    bind[UserIndexerPlugin].to[UserIndexerPluginImpl].in[AppScoped]
    bind[SpellIndexerPlugin].to[SpellIndexerPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): ArticleIndexer = {
    val dir = getIndexDirectory("index.article.directory", backup)
    log.info(s"storing search index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new ArticleIndexer(dir, config, articleStore, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def userIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): UserIndexer = {
    val dir = getIndexDirectory("index.user.directory", backup)
    log.info(s"storing user index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new UserIndexer(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def bookmarkStore(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): BookmarkStore = {
    val dir = getIndexDirectory("index.bookmarkStore.directory", backup)
    log.info(s"storing BookmarkStore in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new BookmarkStore(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def uriGraphIndexer(bookmarkStore: BookmarkStore, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): URIGraphIndexer = {
    val dir = getIndexDirectory("index.urigraph.directory", backup)
    log.info(s"storing URIGraph in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new URIGraphIndexer(dir, config, bookmarkStore, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def collectionNameIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): CollectionNameIndexer = {
    val dir = getIndexDirectory("index.collectionName.directory", backup)
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionNameIndexer(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def collectionIndexer(collectionNameIndexer: CollectionNameIndexer, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): CollectionIndexer = {
    val dir = getIndexDirectory("index.collection.directory", backup)
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionIndexer(dir, config, collectionNameIndexer, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def messageIndexer(backup: IndexStore, eliza: ElizaServiceClient, airbrake: AirbrakeNotifier): MessageIndexer = {
    val dir = getIndexDirectory("index.message.directory", backup)
    log.info(s"storing message index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new MessageIndexer(dir, config, eliza, airbrake)
  }

  @Singleton
  @Provides
  def phraseIndexer(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): PhraseIndexer = {
    val dir = getIndexDirectory("index.phrase.directory", backup)
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
    new PhraseIndexerImpl(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def spellIndexer(backup: IndexStore): SpellIndexer = {
    val spellDir = getIndexDirectory("index.spell.directory", backup)
    val articleDir = getIndexDirectory("index.article.directory", backup)
    SpellIndexer(spellDir, articleDir)
  }

}

case class ProdIndexModule() extends IndexModule {

  protected def getIndexDirectory(dir: String, indexStore: IndexStore): IndexDirectory =
    getPersistentIndexDirectory(current.configuration.getString(dir), indexStore).get
}

case class DevIndexModule() extends IndexModule {
  var volatileDirMap = Map.empty[String, IndexDirectory]  // just in case we need to reference a volatileDir. e.g. in spellIndexer

  protected def getIndexDirectory(dir: String, indexStore: IndexStore): IndexDirectory =
    getPersistentIndexDirectory(current.configuration.getString(dir), indexStore).getOrElse{
      volatileDirMap.getOrElse(dir, {
        val newdir = new VolatileIndexDirectoryImpl()
        volatileDirMap += dir -> newdir
        newdir
      })
    }
}
