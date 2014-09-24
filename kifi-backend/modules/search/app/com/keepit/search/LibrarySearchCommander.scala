package com.keepit.search

import scala.concurrent.Future
import com.keepit.search.sharding.{ Sharding }
import com.keepit.model._
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.engine.{ LibrarySearch, SearchFactory }
import com.keepit.common.akka.SafeFuture
import com.keepit.search.engine.result.LibraryShardResult
import com.keepit.search.sharding.Shard
import com.keepit.search.sharding.ActiveShards
import play.api.libs.json.Json

case class LibrarySearchRequest(
  userId: Id[User],
  experiments: Set[ExperimentType],
  queryString: String,
  filter: Option[String],
  context: Option[String],
  lang1: Lang,
  lang2: Option[Lang],
  maxHits: Int,
  predefinedConfig: Option[SearchConfig])

object LibrarySearchRequest {
  implicit val format = Json.format[LibrarySearchRequest]
}

@ImplementedBy(classOf[LibrarySearchCommanderImpl])
trait LibrarySearchCommander {
  def librarySearch(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    context: Option[String],
    maxHits: Int,
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None): Future[LibraryShardResult]
  def distLibrarySearch(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Seq[LibraryShardResult]]
}

class LibrarySearchCommanderImpl @Inject() (
    activeShards: ActiveShards,
    searchFactory: SearchFactory,
    languageCommander: LanguageCommander,
    mainSearcherFactory: MainSearcherFactory,
    val searchClient: DistributedSearchServiceClient) extends LibrarySearchCommander with Sharding with Logging {

  def librarySearch(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    context: Option[String],
    maxHits: Int,
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None): Future[LibraryShardResult] = {
    val (localShards, remotePlan) = distributionPlan(userId, activeShards)
    languageCommander.getLangs(localShards, remotePlan, userId, query, acceptLangs, None).flatMap {
      case (lang1, lang2) =>
        val request = LibrarySearchRequest(userId, experiments, query, filter, context, lang1, lang2, maxHits, predefinedConfig)
        val futureRemoteLibraryShardResults = searchClient.distLibrarySearch(remotePlan, request)
        val futureLocalLibraryShardResult = distLibrarySearch(localShards, request)
        val configFuture = mainSearcherFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig)
        val futureResults = Future.sequence(futureRemoteLibraryShardResults :+ futureLocalLibraryShardResult)
        val searchFilter = SearchFilter(filter, LibraryContext.None, context)
        for {
          results <- futureResults
          (config, _) <- configFuture
        } yield mergeResults(results.flatten, maxHits, searchFilter, config)
    }
  }

  private def mergeResults(libraryShardResults: Seq[LibraryShardResult], maxHits: Int, filter: SearchFilter, config: SearchConfig): LibraryShardResult = {
    val (myHits, friendsHits, othersHits) = LibrarySearch.partition(libraryShardResults.flatMap(_.hits), maxHits)
    LibrarySearch.merge(myHits, friendsHits, othersHits, maxHits, filter, config)
  }

  def distLibrarySearch(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Seq[LibraryShardResult]] = {
    mainSearcherFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig).flatMap {
      case (searchConfig, _) =>
        val searchFilter = SearchFilter(request.filter, LibraryContext.None, request.context)
        val searches = searchFactory.getLibrarySearches(shards, request.userId, request.queryString, request.lang1, request.lang2, request.maxHits, searchFilter, searchConfig)
        val futureResults: Seq[Future[LibraryShardResult]] = searches.map { librarySearch => SafeFuture { librarySearch.execute() } }
        Future.sequence(futureResults)
    }
  }
}
