package com.keepit.search.index

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.amazon.MyInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.inject.AppScoped
import com.keepit.model.NormalizedURI
import com.keepit.search.ArticleStore
import com.keepit.search.article._
import com.keepit.search.graph._
import com.keepit.search.graph.collection._
import com.keepit.search.graph.bookmark._
import com.keepit.search.graph.user._
import com.keepit.search.message.{ MessageIndexer, MessageIndexerPlugin, MessageIndexerPluginImpl }
import com.keepit.search.phrasedetector.{ PhraseIndexerPluginImpl, PhraseIndexerPlugin, PhraseIndexerImpl, PhraseIndexer }
import com.keepit.search.sharding._
import com.keepit.search.spellcheck.{ SpellIndexerPlugin, SpellIndexerPluginImpl, SpellIndexer }
import com.keepit.search.user._
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.{ Inject, Provides, Singleton }
import org.apache.commons.io.FileUtils
import java.io.File
import play.api.Play._

trait IndexModule extends ScalaModule with Logging {

  protected def getArchivedIndexDirectory(maybeDir: Option[String], indexStore: IndexStore): Option[ArchivedIndexDirectory] = {
    maybeDir.map { d =>
      val dir = new File(d).getCanonicalFile
      val tempDir = new File(current.configuration.getString("search.temporary.directory").get, dir.getName)
      FileUtils.deleteDirectory(tempDir)
      FileUtils.forceMkdir(tempDir)
      tempDir.deleteOnExit()
      val indexDirectory = new ArchivedIndexDirectory(dir, tempDir, indexStore)
      if (!dir.exists()) {
        try {
          val t1 = currentDateTime.getMillis
          indexDirectory.restoreFromBackup()
          val t2 = currentDateTime.getMillis
          log.info(s"$d was restored from S3 in ${(t2 - t1) / 1000} seconds")
        } catch {
          case e: Exception => {
            log.error(s"Could not restore $dir from S3}", e)
            FileUtils.deleteDirectory(dir)
            FileUtils.forceMkdir(dir)
          }
        }
      }
      indexDirectory
    }
  }

  protected def getIndexDirectory(configName: String, shard: Shard[_], indexStore: IndexStore): IndexDirectory

  def configure() {
    bind[PhraseIndexerPlugin].to[PhraseIndexerPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[CollectionGraphPlugin].to[CollectionGraphPluginImpl].in[AppScoped]
    bind[MessageIndexerPlugin].to[MessageIndexerPluginImpl].in[AppScoped]
    bind[UserIndexerPlugin].to[UserIndexerPluginImpl].in[AppScoped]
    bind[SpellIndexerPlugin].to[SpellIndexerPluginImpl].in[AppScoped]
    bind[UserGraphPlugin].to[UserGraphPluginImpl].in[AppScoped]
    bind[SearchFriendGraphPlugin].to[SearchFriendGraphPluginImpl].in[AppScoped]
  }

  private[this] val noShard = Shard[Any](0, 1)

  @Singleton
  @Provides
  def activeShards(myAmazonInstanceInfo: MyInstanceInfo): ActiveShards = {
    val shards = (new ShardSpecParser).parse[NormalizedURI](
      myAmazonInstanceInfo.info.tags.get("ShardSpec") match {
        case Some(spec) =>
          log.info(s"using the shard spec [$spec] from ec2 tag")
          spec
        case _ =>
          current.configuration.getString("index.shards") match {
            case Some(spec) =>
              log.info(s"using the shard spec [$spec] from config")
              spec
            case None =>
              log.error("no shard spec found")
              throw new Exception("no shard spec found")
          }
      }
    )
    if (shards.isEmpty) {
      log.error("no shard spec found")
      throw new Exception("no shard spec found")
    }
    ActiveShards(shards)
  }

  @Singleton
  @Provides
  def shardedArticleIndexer(activeShards: ActiveShards, articleStore: ArticleStore, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): ShardedArticleIndexer = {
    def articleIndexer(shard: Shard[NormalizedURI]) = {
      val dir = getIndexDirectory("index.article.directory", shard, backup)
      log.info(s"storing ArticleIndex${shard.indexNameSuffix} in $dir")
      new ArticleIndexer(dir, articleStore, airbrake)
    }

    val indexShards = activeShards.local.map { shard => (shard, articleIndexer(shard)) }
    new ShardedArticleIndexer(indexShards.toMap, articleStore, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def shardedURIGraphIndexer(activeShards: ActiveShards, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): ShardedURIGraphIndexer = {
    def bookmarkStore(shard: Shard[NormalizedURI]) = {
      val dir = getIndexDirectory("index.bookmarkStore.directory", shard, backup)
      log.info(s"storing BookmarkStore${shard.indexNameSuffix} in $dir")
      new BookmarkStore(dir, airbrake)
    }
    def uriGraphIndexer(shard: Shard[NormalizedURI], store: BookmarkStore): URIGraphIndexer = {
      val dir = getIndexDirectory("index.urigraph.directory", shard, backup)
      log.info(s"storing URIGraphIndex${shard.indexNameSuffix} in $dir")
      new URIGraphIndexer(dir, store, airbrake)
    }

    val indexShards = activeShards.local.map { shard => (shard, uriGraphIndexer(shard, bookmarkStore(shard))) }
    new ShardedURIGraphIndexer(indexShards.toMap, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def shardedCollectionIndexer(activeShards: ActiveShards, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): ShardedCollectionIndexer = {
    def collectionNameIndexer(shard: Shard[NormalizedURI]) = {
      val dir = getIndexDirectory("index.collectionName.directory", shard, backup)
      log.info(s"storing CollectionNameIndex${shard.indexNameSuffix} in $dir")
      new CollectionNameIndexer(dir, airbrake)
    }
    def collectionIndexer(shard: Shard[NormalizedURI], collectionNameIndexer: CollectionNameIndexer): CollectionIndexer = {
      val dir = getIndexDirectory("index.collection.directory", shard, backup)
      log.info(s"storing CollectionIndex${shard.indexNameSuffix} in $dir")
      new CollectionIndexer(dir, collectionNameIndexer, airbrake)
    }

    val indexShards = activeShards.local.map { shard => (shard, collectionIndexer(shard, collectionNameIndexer(shard))) }
    new ShardedCollectionIndexer(indexShards.toMap, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def userIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): UserIndexer = {
    val dir = getIndexDirectory("index.user.directory", noShard, backup)
    log.info(s"storing user index in $dir")
    new UserIndexer(dir, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def userGraphIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): UserGraphIndexer = {
    val dir = getIndexDirectory("index.userGraph.directory", noShard, backup)
    log.info(s"storing user graph index in $dir")
    new UserGraphIndexer(dir, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def searchFriendIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): SearchFriendIndexer = {
    val dir = getIndexDirectory("index.searchFriend.directory", noShard, backup)
    log.info(s"storing searchFriend index in $dir")
    new SearchFriendIndexer(dir, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def messageIndexer(backup: IndexStore, eliza: ElizaServiceClient, airbrake: AirbrakeNotifier): MessageIndexer = {
    val dir = getIndexDirectory("index.message.directory", noShard, backup)
    log.info(s"storing message index in $dir")
    new MessageIndexer(dir, eliza, airbrake)
  }

  @Singleton
  @Provides
  def phraseIndexer(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): PhraseIndexer = {
    val dir = getIndexDirectory("index.phrase.directory", noShard, backup)
    val dataDir = current.configuration.getString("index.config").map { path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    new PhraseIndexerImpl(dir, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def spellIndexer(backup: IndexStore, shardedArticleIndexer: ShardedArticleIndexer): SpellIndexer = {
    val spellDir = getIndexDirectory("index.spell.directory", noShard, backup)
    SpellIndexer(spellDir, shardedArticleIndexer)
  }

}

case class ProdIndexModule() extends IndexModule {

  protected def getIndexDirectory(configName: String, shard: Shard[_], indexStore: IndexStore): IndexDirectory =
    getArchivedIndexDirectory(current.configuration.getString(configName).map(_ + shard.indexNameSuffix), indexStore).get
}

case class DevIndexModule() extends IndexModule {
  var volatileDirMap = Map.empty[(String, Shard[_]), IndexDirectory] // just in case we need to reference a volatileDir. e.g. in spellIndexer

  protected def getIndexDirectory(configName: String, shard: Shard[_], indexStore: IndexStore): IndexDirectory =
    getArchivedIndexDirectory(current.configuration.getString(configName).map(_ + shard.indexNameSuffix), indexStore).getOrElse {
      volatileDirMap.getOrElse((configName, shard), {
        val newdir = new VolatileIndexDirectory()
        volatileDirMap += (configName, shard) -> newdir
        newdir
      })
    }
}
