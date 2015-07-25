package com.keepit.search.controllers

import com.keepit.search.engine.uri.{ UriShardResult, UriSearchResult, UriSearchExplanation }
import com.keepit.search.index._
import com.keepit.common.util.Configuration
import com.keepit.search._
import com.keepit.inject.AppScoped
import com.keepit.common.db.Id
import com.keepit.model._
import scala.concurrent.Future
import com.keepit.search.index.sharding.Shard

case class FixedResultIndexModule() extends IndexModule {
  var volatileDirMap = Map.empty[(String, Shard[_]), IndexDirectory] // just in case we need to reference a volatileDir. e.g. in spellIndexer

  protected def removeOldIndexDirs(conf: Configuration, configName: String, shard: Shard[_], versionsToClean: Seq[IndexerVersion]): Unit = {}

  protected def getIndexDirectory(configName: String, shard: Shard[_], version: IndexerVersion, indexStore: IndexStore, conf: Configuration, versionsToClean: Seq[IndexerVersion]): IndexDirectory = {
    volatileDirMap.getOrElse((configName, shard), {
      val newdir = new VolatileIndexDirectory()
      volatileDirMap += (configName, shard) -> newdir
      newdir
    })
  }

  override def configure() {
    super.configure()
    bind[UriSearchCommander].to[FixedResultUriSearchCommander].in[AppScoped]
  }
}

class FixedResultUriSearchCommander extends UriSearchCommander {

  private var plainResults: Map[String, UriSearchResult] = Map.empty

  def setPlainResults(results: Map[String, UriSearchResult]): Unit = { plainResults = results }

  def searchUris(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[UserExperimentType],
    query: String,
    context: Future[SearchContext],
    maxHits: Int,
    lastUUIDStr: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None) = Future.successful(plainResults(query))

  def distSearchUris(
    shards: Set[Shard[NormalizedURI]],
    request: UriSearchRequest): Future[UriShardResult] = ???

  def explain(userId: Id[User], uriId: Id[NormalizedURI], libraryId: Option[Id[Library]], lang: Option[String], experiments: Set[UserExperimentType], query: String, debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean): Future[Option[UriSearchExplanation]] = ???
  def warmUp(userId: Id[User]): Unit = {}
  def findShard(uriId: Id[NormalizedURI]): Option[Shard[NormalizedURI]] = ???
}
