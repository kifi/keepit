package com.keepit.search.index

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
import com.keepit.search.spellcheck.{SpellIndexerPlugin, SpellIndexerPluginImpl, SpellIndexer}
import com.keepit.inject.AppScoped
import java.io.File
import com.keepit.common.logging.Logging
import com.keepit.search.user.UserIndexer
import com.keepit.search.user.UserIndexerPlugin
import com.keepit.search.user.UserIndexerPluginImpl
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import org.apache.commons.io.FileUtils
import com.keepit.search.sharding.ActiveShards
import com.keepit.search.sharding.ActiveShardsSpecParser
import com.keepit.search.sharding.Shard
import com.keepit.search.sharding.ShardedArticleIndexer
import com.keepit.search.sharding.ShardedURIGraphIndexer
import com.keepit.search.article.ArticleIndexerPluginImpl
import com.keepit.search.article.ArticleIndexerPlugin
import com.keepit.search.article.ArticleIndexer
import com.keepit.search.graph.collection._
import com.keepit.search.graph.bookmark._
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.keepit.search.ArticleStore

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

  protected def getIndexDirectory(configName: String, shard: Shard, indexStore: IndexStore): IndexDirectory

  def configure() {
    bind[PhraseIndexerPlugin].to[PhraseIndexerPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[CollectionGraphPlugin].to[CollectionGraphPluginImpl].in[AppScoped]
    bind[MessageIndexerPlugin].to[MessageIndexerPluginImpl].in[AppScoped]
    bind[UserIndexerPlugin].to[UserIndexerPluginImpl].in[AppScoped]
    bind[SpellIndexerPlugin].to[SpellIndexerPluginImpl].in[AppScoped]
  }

  private[this] val noShard = Shard(0, 1)

  @Singleton
  @Provides
  def activeShards: ActiveShards = {
    ActiveShardsSpecParser(
      Option(System.getProperty("index.shards")) orElse current.configuration.getString("index.shards")
    )
  }

  @Singleton
  @Provides
  def shardedArticleIndexer(activeShards: ActiveShards, articleStore: ArticleStore, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): ShardedArticleIndexer = {
    def articleIndexer(shard: Shard) = {
      val dir = getIndexDirectory("index.article.directory", shard, backup)
      log.info(s"storing ArticleIndex (shard=$shard.shardId) in $dir")
      val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
      new ArticleIndexer(dir, config, articleStore, airbrake, shoeboxClient)
    }

    val indexShards = activeShards.shards.map{ shard => (shard, articleIndexer(shard)) }
    new ShardedArticleIndexer(indexShards.toMap, articleStore, shoeboxClient)
  }

  //TODO: enable
  def shardedURIGraphIndexer(activeShards: ActiveShards, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): ShardedURIGraphIndexer = {
    def bookmarkStore(shard: Shard) = {
      val dir = getIndexDirectory("index.bookmarkStore.directory", shard, backup)
      log.info(s"storing BookmarkStore (shard=$shard.shardId) in $dir")
      val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
      new BookmarkStore(dir, config, airbrake, shoeboxClient)
    }
    def uriGraphIndexer(shard: Shard, store: BookmarkStore): URIGraphIndexer = {
      val dir = getIndexDirectory("index.urigraph.directory", noShard, backup)
      log.info(s"storing URIGraph (shard=$shard.shardId) in $dir")
      val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
      new URIGraphIndexer(dir, config, store, airbrake, shoeboxClient)
    }

    val indexShards = activeShards.shards.map{ shard => (shard, uriGraphIndexer(shard, bookmarkStore(shard))) }
    new ShardedURIGraphIndexer(indexShards.toMap, shoeboxClient)
  }

  private def bookmarkStore(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): BookmarkStore = {
    val dir = getIndexDirectory("index.bookmarkStore.directory", noShard, backup)
    log.info(s"storing BookmarkStore in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new BookmarkStore(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def uriGraphIndexer(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): URIGraphIndexer = {
    val store = bookmarkStore(backup, airbrake, shoeboxClient)
    val dir = getIndexDirectory("index.urigraph.directory", noShard, backup)
    log.info(s"storing URIGraph in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new URIGraphIndexer(dir, config, store, airbrake, shoeboxClient)
  }

  private def collectionNameIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): CollectionNameIndexer = {
    val dir = getIndexDirectory("index.collectionName.directory", noShard, backup)
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionNameIndexer(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def collectionIndexer(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): CollectionIndexer = {
    val nameIndexer = collectionNameIndexer(airbrake, backup, shoeboxClient)
    val dir = getIndexDirectory("index.collection.directory", noShard, backup)
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionIndexer(dir, config, nameIndexer, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def userIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): UserIndexer = {
    val dir = getIndexDirectory("index.user.directory", noShard, backup)
    log.info(s"storing user index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new UserIndexer(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def messageIndexer(backup: IndexStore, eliza: ElizaServiceClient, airbrake: AirbrakeNotifier): MessageIndexer = {
    val dir = getIndexDirectory("index.message.directory", noShard, backup)
    log.info(s"storing message index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new MessageIndexer(dir, config, eliza, airbrake)
  }

  @Singleton
  @Provides
  def phraseIndexer(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): PhraseIndexer = {
    val dir = getIndexDirectory("index.phrase.directory", noShard, backup)
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
    val spellDir = getIndexDirectory("index.spell.directory", noShard, backup)
    val articleDir = getIndexDirectory("index.article.directory", noShard, backup)
    SpellIndexer(spellDir, articleDir)
  }

}

case class ProdIndexModule() extends IndexModule {

  protected def getIndexDirectory(configName: String, shard: Shard, indexStore: IndexStore): IndexDirectory =
    getPersistentIndexDirectory(current.configuration.getString(configName).map(_ + shard.indexNameSuffix), indexStore).get
}

case class DevIndexModule() extends IndexModule {
  var volatileDirMap = Map.empty[(String, Shard), IndexDirectory]  // just in case we need to reference a volatileDir. e.g. in spellIndexer

  protected def getIndexDirectory(configName: String, shard: Shard, indexStore: IndexStore): IndexDirectory =
    getPersistentIndexDirectory(current.configuration.getString(configName).map(_ + shard.indexNameSuffix), indexStore).getOrElse{
      volatileDirMap.getOrElse((configName, shard), {
        val newdir = new VolatileIndexDirectoryImpl()
        volatileDirMap += (configName, shard) -> newdir
        newdir
      })
    }
}
