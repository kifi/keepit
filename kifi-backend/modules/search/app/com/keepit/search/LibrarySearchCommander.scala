package com.keepit.search

import com.keepit.search.engine.result.LibraryShardResult
import scala.concurrent.Future
import com.keepit.search.sharding.{ ActiveShards, Sharding, Shard }
import com.keepit.model.{ ExperimentType, User, NormalizedURI }
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.engine.SearchFactory
import com.keepit.common.akka.SafeFuture

case class LibrarySearchRequest(
  userId: Id[User],
  experiments: Set[ExperimentType],
  queryString: String,
  filter: SearchFilter,
  lang1: Lang,
  lang2: Option[Lang],
  maxHits: Int,
  predefinedConfig: Option[SearchConfig] = None)

case class LibrarySearchResult()

@ImplementedBy(classOf[LibrarySearchCommanderImpl])
trait LibrarySearchCommander {
  def librarySearch(request: LibrarySearchRequest): Future[LibrarySearchResult]
  def distLibrarySearch(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Seq[LibraryShardResult]]
}

class LibrarySearchCommanderImpl @Inject() (
    activeShards: ActiveShards,
    searchFactory: SearchFactory,
    mainSearcherFactory: MainSearcherFactory,
    val searchClient: DistributedSearchServiceClient) extends LibrarySearchCommander with Sharding with Logging {

  def librarySearch(request: LibrarySearchRequest): Future[LibrarySearchResult] = {
    val (localShards, remotePlan) = distributionPlan(request.userId, activeShards)
    val futureRemoteLibraryShardResults = searchClient.distLibrarySearch(remotePlan, request)
    val futureLocalLibraryShardResult = distLibrarySearch(localShards, request)
    Future.sequence(futureRemoteLibraryShardResults :+ futureLocalLibraryShardResult).map(results => mergeResults(results.flatten))
  }

  private def mergeResults(libraryShardResults: Seq[LibraryShardResult]): LibrarySearchResult = ???

  def distLibrarySearch(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Seq[LibraryShardResult]] = {
    mainSearcherFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig).flatMap {
      case (searchConfig, _) =>
        val searches = searchFactory.getLibrarySearches(shards, request.userId, request.queryString, request.lang1, request.lang2, request.maxHits, request.filter, searchConfig)
        val futureResults: Seq[Future[LibraryShardResult]] = searches.map { librarySearch => SafeFuture { librarySearch.execute() } }
        Future.sequence(futureResults)
    }
  }
}
