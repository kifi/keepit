package com.keepit.search

import com.keepit.search.engine.result.LibraryShardResult
import scala.concurrent.Future
import com.keepit.search.sharding.{ ActiveShards, Sharding, Shard }
import com.keepit.model.{ User, NormalizedURI }
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits.defaultContext

case class LibrarySearchRequest(userId: Id[User])

case class LibrarySearchResult()

@ImplementedBy(classOf[LibrarySearchCommanderImpl])
trait LibrarySearchCommander {
  def librarySearch(request: LibrarySearchRequest): Future[LibrarySearchResult]
  def distLibrarySearch(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Set[LibraryShardResult]]
}

class LibrarySearchCommanderImpl @Inject() (
    activeShards: ActiveShards,
    val searchClient: DistributedSearchServiceClient) extends LibrarySearchCommander with Sharding with Logging {

  def librarySearch(request: LibrarySearchRequest): Future[LibrarySearchResult] = {
    val (localShards, remotePlan) = distributionPlan(request.userId, activeShards)
    val futureRemoteLibraryShardResults = searchClient.distLibrarySearch(remotePlan, request)
    val futureLocalLibraryShardResult = distLibrarySearch(localShards, request)
    Future.sequence(futureRemoteLibraryShardResults :+ futureLocalLibraryShardResult).map(results => mergeResults(results.flatten))
  }

  def distLibrarySearch(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Set[LibraryShardResult]] = {
    ???
  }

  private def mergeResults(libraryShardResults: Seq[LibraryShardResult]): LibrarySearchResult = ???

}
