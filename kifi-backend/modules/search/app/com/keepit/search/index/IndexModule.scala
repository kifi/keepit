package com.keepit.search.index

import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.rover.RoverServiceClient
import com.keepit.search.index.graph.library.membership.{ LibraryMembershipIndexerPluginImpl, LibraryMembershipIndexerPlugin, LibraryMembershipIndexer }
import com.keepit.search.index.graph.organization._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.amazon.MyInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.inject.AppScoped
import com.keepit.model.NormalizedURI
import com.keepit.search.index.article._
import com.keepit.search.index.graph.user._
import com.keepit.search.index.message.{ MessageIndexer, MessageIndexerPlugin, MessageIndexerPluginImpl }
import com.keepit.search.index.phrase.{ PhraseIndexerPluginImpl, PhraseIndexerPlugin, PhraseIndexerImpl, PhraseIndexer }
import com.keepit.search.index.sharding._
import com.keepit.search.index.user._
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.{ Provides, Singleton }
import org.apache.commons.io.FileUtils
import java.io.File
import com.keepit.search.index.graph.library.{ LibraryIndexerPluginImpl, LibraryIndexerPlugin, LibraryIndexer }
import com.keepit.search.index.graph.keep.{ KeepIndexer, KeepIndexerPluginImpl, KeepIndexerPlugin, ShardedKeepIndexer }
import com.keepit.common.util.Configuration

import scala.concurrent.ExecutionContext

trait IndexModule extends ScalaModule with Logging {

  protected def getArchivedIndexDirectory(maybeDir: Option[String], indexStore: IndexStore, conf: Configuration): Option[ArchivedIndexDirectory] = {
    maybeDir.map { d =>
      val dir = new File(d).getCanonicalFile
      val tempDir = new File(conf.getString("search.temporary.directory").get, dir.getName)
      FileUtils.deleteDirectory(tempDir)
      FileUtils.forceMkdir(tempDir)
      tempDir.deleteOnExit()
      val indexDirectory = new ArchivedIndexDirectory(dir, tempDir, indexStore)
      if (!dir.exists() || dir.list.isEmpty) {
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

  protected def getIndexDirectory(configName: String, shard: Shard[_], version: IndexerVersion, indexStore: IndexStore, conf: Configuration, versionsToClean: Seq[IndexerVersion]): IndexDirectory
  protected def removeOldIndexDirs(conf: Configuration, configName: String, shard: Shard[_], versionsToClean: Seq[IndexerVersion]): Unit

  protected def constructIndexDirName(conf: Configuration, configName: String, shard: Shard[_], version: IndexerVersion): Option[String] = {
    conf.getString(configName).map(_ + indexNameSuffix(shard, version))
  }

  def configure() {
    bind[PhraseIndexerPlugin].to[PhraseIndexerPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[MessageIndexerPlugin].to[MessageIndexerPluginImpl].in[AppScoped]
    bind[UserIndexerPlugin].to[UserIndexerPluginImpl].in[AppScoped]
    bind[UserGraphPlugin].to[UserGraphPluginImpl].in[AppScoped]
    bind[SearchFriendGraphPlugin].to[SearchFriendGraphPluginImpl].in[AppScoped]
    bind[LibraryIndexerPlugin].to[LibraryIndexerPluginImpl].in[AppScoped]
    bind[LibraryMembershipIndexerPlugin].to[LibraryMembershipIndexerPluginImpl].in[AppScoped]
    bind[KeepIndexerPlugin].to[KeepIndexerPluginImpl].in[AppScoped]
    bind[OrganizationIndexerPlugin].to[OrganizationIndexerPluginImpl].in[AppScoped]
    bind[OrganizationMembershipIndexerPlugin].to[OrganizationMembershipIndexerPluginImpl].in[AppScoped]
  }

  private[this] val noShard = Shard[Any](0, 1)

  protected def indexNameSuffix(shard: Shard[_], version: IndexerVersion): String = version.indexNameSuffix + shard.indexNameSuffix

  @Singleton
  @Provides
  def activeShards(myAmazonInstanceInfo: MyInstanceInfo, conf: Configuration): ActiveShards = {
    val shards = (new ShardSpecParser).parse[NormalizedURI](
      myAmazonInstanceInfo.info.tags.get("ShardSpec") match {
        case Some(spec) =>
          log.info(s"using the shard spec [$spec] from ec2 tag")
          spec
        case _ =>
          conf.getString("index.shards") match {
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
  def userIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient, conf: Configuration, serviceDisovery: ServiceDiscovery): UserIndexer = {
    val version = IndexerVersionProviders.User.getVersionByStatus(serviceDisovery)
    val dir = getIndexDirectory("index.user.directory", noShard, version, backup, conf, IndexerVersionProviders.User.getVersionsForCleanup())
    log.info(s"storing user index ${indexNameSuffix(noShard, version)} in $dir")
    new UserIndexer(dir, shoeboxClient, airbrake)
  }

  @Singleton
  @Provides
  def userGraphIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient, conf: Configuration, serviceDisovery: ServiceDiscovery): UserGraphIndexer = {
    val version = IndexerVersionProviders.UserGraph.getVersionByStatus(serviceDisovery)
    val dir = getIndexDirectory("index.userGraph.directory", noShard, version, backup, conf, IndexerVersionProviders.UserGraph.getVersionsForCleanup())
    log.info(s"storing user graph index ${indexNameSuffix(noShard, version)} in $dir")
    new UserGraphIndexer(dir, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def searchFriendIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient, conf: Configuration, serviceDisovery: ServiceDiscovery): SearchFriendIndexer = {
    val version = IndexerVersionProviders.SearchFriend.getVersionByStatus(serviceDisovery)
    val dir = getIndexDirectory("index.searchFriend.directory", noShard, version, backup, conf, IndexerVersionProviders.SearchFriend.getVersionsForCleanup())
    log.info(s"storing searchFriend index ${indexNameSuffix(noShard, version)} in $dir")
    new SearchFriendIndexer(dir, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def messageIndexer(backup: IndexStore, eliza: ElizaServiceClient, airbrake: AirbrakeNotifier, conf: Configuration, serviceDisovery: ServiceDiscovery): MessageIndexer = {
    val version = IndexerVersionProviders.Message.getVersionByStatus(serviceDisovery)
    val dir = getIndexDirectory("index.message.directory", noShard, version, backup, conf, IndexerVersionProviders.Message.getVersionsForCleanup())
    log.info(s"storing message index ${indexNameSuffix(noShard, version)} in $dir")
    new MessageIndexer(dir, eliza, airbrake)
  }

  @Singleton
  @Provides
  def phraseIndexer(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient, conf: Configuration, serviceDisovery: ServiceDiscovery): PhraseIndexer = {
    val version = IndexerVersionProviders.Phrase.getVersionByStatus(serviceDisovery)
    val dir = getIndexDirectory("index.phrase.directory", noShard, version, backup, conf, IndexerVersionProviders.Phrase.getVersionsForCleanup())
    val dataDir = conf.getString("index.config").map { path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    new PhraseIndexerImpl(dir, airbrake, shoeboxClient)
  }

  @Provides @Singleton
  def libraryIndexer(backup: IndexStore, shoebox: ShoeboxServiceClient, airbrake: AirbrakeNotifier, conf: Configuration, serviceDisovery: ServiceDiscovery): LibraryIndexer = {
    val version = IndexerVersionProviders.Library.getVersionByStatus(serviceDisovery)
    val libraryDir = getIndexDirectory("index.library.directory", noShard, version, backup, conf, IndexerVersionProviders.Library.getVersionsForCleanup())
    log.info(s"storing library index ${indexNameSuffix(noShard, version)} in $libraryDir")
    new LibraryIndexer(libraryDir, shoebox, airbrake)
  }

  @Provides @Singleton
  def libraryMembershipIndexer(backup: IndexStore, shoebox: ShoeboxServiceClient, airbrake: AirbrakeNotifier, conf: Configuration, serviceDisovery: ServiceDiscovery): LibraryMembershipIndexer = {
    val version = IndexerVersionProviders.LibraryMembership.getVersionByStatus(serviceDisovery)
    val libraryDir = getIndexDirectory("index.libraryMembership.directory", noShard, version, backup, conf, IndexerVersionProviders.LibraryMembership.getVersionsForCleanup())
    log.info(s"storing library membership index ${indexNameSuffix(noShard, version)} in $libraryDir")
    new LibraryMembershipIndexer(libraryDir, shoebox, airbrake)
  }

  @Provides @Singleton
  def shardedKeepIndexer(activeShards: ActiveShards, backup: IndexStore, shoeboxClient: ShoeboxServiceClient, elizaClient: ElizaServiceClient, airbrake: AirbrakeNotifier, conf: Configuration, serviceDisovery: ServiceDiscovery): ShardedKeepIndexer = {
    val version = IndexerVersionProviders.Keep.getVersionByStatus(serviceDisovery)
    def keepIndexer(shard: Shard[NormalizedURI]) = {
      val dir = getIndexDirectory("index.keep.directory", shard, version, backup, conf, IndexerVersionProviders.Keep.getVersionsForCleanup())
      log.info(s"storing KeepIndex ${indexNameSuffix(shard, version)} in $dir")
      new KeepIndexer(dir, shard, airbrake)
    }

    val indexShards = activeShards.local.map { shard => (shard, keepIndexer(shard)) }
    new ShardedKeepIndexer(indexShards.toMap, shoeboxClient, elizaClient, airbrake)
  }

  @Provides @Singleton
  def shardedArticleIndexer(activeShards: ActiveShards, backup: IndexStore, shoebox: ShoeboxServiceClient, rover: RoverServiceClient, airbrake: AirbrakeNotifier, conf: Configuration, serviceDiscovery: ServiceDiscovery, executionContext: ExecutionContext): ShardedArticleIndexer = {
    val version = IndexerVersionProviders.Article.getVersionByStatus(serviceDiscovery)
    def articleIndexer(shard: Shard[NormalizedURI]) = {
      val dir = getIndexDirectory("index.article.directory", shard, version, backup, conf, IndexerVersionProviders.Article.getVersionsForCleanup())
      log.info(s"storing ArticleIndex ${indexNameSuffix(shard, version)} in $dir")
      new ArticleIndexer(dir, shard, airbrake)
    }

    val indexShards = activeShards.local.map { shard => (shard, articleIndexer(shard)) }
    new ShardedArticleIndexer(indexShards.toMap, shoebox, rover, airbrake, executionContext)
  }

  @Provides @Singleton
  def organizationIndexer(backup: IndexStore, shoebox: ShoeboxServiceClient, airbrake: AirbrakeNotifier, conf: Configuration, serviceDisovery: ServiceDiscovery): OrganizationIndexer = {
    val version = IndexerVersionProviders.Organization.getVersionByStatus(serviceDisovery)
    val orgDir = getIndexDirectory("index.organization.directory", noShard, version, backup, conf, IndexerVersionProviders.Organization.getVersionsForCleanup())
    log.info(s"storing organization index ${indexNameSuffix(noShard, version)} in $orgDir")
    new OrganizationIndexer(orgDir, shoebox, airbrake)
  }

  @Provides @Singleton
  def organizationMembershipIndexer(backup: IndexStore, shoebox: ShoeboxServiceClient, airbrake: AirbrakeNotifier, conf: Configuration, serviceDisovery: ServiceDiscovery): OrganizationMembershipIndexer = {
    val version = IndexerVersionProviders.OrganizationMembership.getVersionByStatus(serviceDisovery)
    val orgMemDir = getIndexDirectory("index.organizationMembership.directory", noShard, version, backup, conf, IndexerVersionProviders.OrganizationMembership.getVersionsForCleanup())
    log.info(s"storing organization index ${indexNameSuffix(noShard, version)} in $orgMemDir")
    new OrganizationMembershipIndexer(orgMemDir, shoebox, airbrake)
  }

}

case class ProdIndexModule() extends IndexModule {

  def removeOldIndexDirs(conf: Configuration, configName: String, shard: Shard[_], versionsToClean: Seq[IndexerVersion]): Unit = {
    versionsToClean.foreach { version =>
      constructIndexDirName(conf, configName, shard, version).foreach { dir =>
        log.info(s"deleting directory ${dir} if it exists")
        FileUtils.deleteDirectory(new File(dir))
      }
    }
  }

  protected def getIndexDirectory(configName: String, shard: Shard[_], version: IndexerVersion, indexStore: IndexStore, conf: Configuration, versionsToClean: Seq[IndexerVersion]): IndexDirectory = {
    removeOldIndexDirs(conf, configName, shard, versionsToClean)
    getArchivedIndexDirectory(constructIndexDirName(conf, configName, shard, version), indexStore, conf).get
  }

}

case class DevIndexModule() extends IndexModule {
  var volatileDirMap = Map.empty[(String, Shard[_]), IndexDirectory] // just in case we need to reference a volatileDir. e.g. in spellIndexer

  def removeOldIndexDirs(conf: Configuration, configName: String, shard: Shard[_], versionsToClean: Seq[IndexerVersion]): Unit = {}

  protected def getIndexDirectory(configName: String, shard: Shard[_], version: IndexerVersion, indexStore: IndexStore, conf: Configuration, versionsToClean: Seq[IndexerVersion]): IndexDirectory =
    getArchivedIndexDirectory(constructIndexDirName(conf, configName, shard, version), indexStore, conf).getOrElse {
      volatileDirMap.getOrElse((configName, shard), {
        val newdir = new VolatileIndexDirectory()
        volatileDirMap += (configName, shard) -> newdir
        newdir
      })
    }
}
