package com.keepit.search.controllers

import com.keepit.search.index._
import com.keepit.common.util.Configuration
import com.keepit.search._
import com.keepit.inject.AppScoped
import com.keepit.search.result._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model._
import play.api.libs.json.Json
import com.keepit.social.BasicUser
import scala.concurrent.Future
import play.api.libs.json.JsArray
import com.keepit.search.result.DecoratedResult
import com.keepit.model.Username
import com.keepit.search.sharding.Shard
import com.keepit.search.engine.result.{ KifiPlainResult, KifiShardResult }
import com.keepit.search.engine.explain.Explanation
import com.keepit.search.augmentation.{ AugmentedItem, FullAugmentationInfo, RestrictedKeepInfo, AugmentationCommander }

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
    bind[SearchCommander].to[FixedResultSearchCommander].in[AppScoped]
  }
}

class FixedResultSearchCommander extends SearchCommander {

  private var decoratedResults: Map[String, DecoratedResult] = Map.empty
  private var plainResults: Map[String, KifiPlainResult] = Map.empty

  def setDecoratedResults(results: Map[String, DecoratedResult]): Unit = { decoratedResults = results }
  def setPlainResults(results: Map[String, KifiPlainResult]): Unit = { plainResults = results }

  def search(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false): DecoratedResult = decoratedResults(query)

  def distSearch(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig],
    debug: Option[String]): PartialSearchResult = ???

  def search2(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    libraryContextFuture: Future[LibraryContext],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None) = Future.successful(plainResults(query))

  def distSearch2(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    library: LibraryContext,
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig],
    debug: Option[String]): Future[KifiShardResult] = ???

  def explain(userId: Id[User], uriId: Id[NormalizedURI], lang: Option[String], experiments: Set[ExperimentType], query: String, debug: Option[String]): Future[Option[Explanation]] = ???
  def warmUp(userId: Id[User]): Unit = {}
  def findShard(uriId: Id[NormalizedURI]): Option[Shard[NormalizedURI]] = ???
}
