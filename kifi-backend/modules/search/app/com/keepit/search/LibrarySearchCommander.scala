package com.keepit.search

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.json.EitherFormat
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.engine.library.{ LibrarySearchExplanation, LibrarySearch, LibraryShardHit, LibraryShardResult }
import com.keepit.search.engine.{ DebugOption, SearchFactory }
import com.keepit.search.index.sharding.{ ActiveShards, Shard, Sharding }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

case class LibrarySearchRequest(
  userId: Id[User],
  experiments: Set[ExperimentType],
  queryString: String,
  filter: Option[Either[Id[User], String]],
  context: Option[String],
  lang1: Lang,
  lang2: Option[Lang],
  maxHits: Int,
  disablePrefixSearch: Boolean,
  predefinedConfig: Option[SearchConfig],
  debug: Option[String],
  explain: Option[Id[Library]])

object LibrarySearchRequest {
  implicit val format = {
    implicit val filterFormat = EitherFormat[Id[User], String]
    Json.format[LibrarySearchRequest]
  }
}

case class LibrarySearchResult(hits: Seq[LibraryShardHit], show: Boolean, idFilter: Set[Long], searchExperimentId: Option[Id[SearchConfigExperiment]], explanation: Option[LibrarySearchExplanation])

@ImplementedBy(classOf[LibrarySearchCommanderImpl])
trait LibrarySearchCommander {
  def searchLibraries(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filterFuture: Future[Option[Either[Id[User], String]]],
    context: Option[String],
    maxHits: Int,
    disablePrefixSearch: Boolean,
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None,
    explain: Option[Id[Library]]): Future[LibrarySearchResult]
  def distSearchLibraries(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Seq[LibraryShardResult]]
}

class LibrarySearchCommanderImpl @Inject() (
    activeShards: ActiveShards,
    searchFactory: SearchFactory,
    languageCommander: LanguageCommander,
    val searchClient: DistributedSearchServiceClient) extends LibrarySearchCommander with Sharding with Logging {

  def searchLibraries(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    futureFilter: Future[Option[Either[Id[User], String]]],
    context: Option[String],
    maxHits: Int,
    disablePrefixSearch: Boolean,
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None,
    explain: Option[Id[Library]]): Future[LibrarySearchResult] = {
    val (localShards, remotePlan) = distributionPlan(userId, activeShards)
    val futureLangs = languageCommander.getLangs(localShards, remotePlan, userId, query, acceptLangs, LibraryContext.None)
    futureFilter.flatMap { filter =>
      futureLangs.flatMap {
        case (lang1, lang2) =>
          val request = LibrarySearchRequest(userId, experiments, query, filter, context, lang1, lang2, maxHits, disablePrefixSearch, predefinedConfig, debug, explain)
          val futureRemoteLibraryShardResults = searchClient.distSearchLibraries(remotePlan, request)
          val futureLocalLibraryShardResult = distSearchLibraries(localShards, request)
          val configFuture = searchFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig)
          val futureResults = Future.sequence(futureRemoteLibraryShardResults :+ futureLocalLibraryShardResult)
          val searchFilter = SearchFilter(filter, LibraryContext.None, context)
          for {
            results <- futureResults
            (config, searchExperimentId) <- configFuture
          } yield toLibrarySearchResult(results.flatten, maxHits, searchFilter, config, searchExperimentId)
      }
    }
  }

  private def toLibrarySearchResult(libraryShardResults: Seq[LibraryShardResult], maxHits: Int, filter: SearchFilter, config: SearchConfig, searchExperimentId: Option[Id[SearchConfigExperiment]]): LibrarySearchResult = {
    val uniqueHits = libraryShardResults.flatMap(_.hits).groupBy(_.id).mapValues(_.maxBy(_.score)).values.toSeq
    val bestExplanation = libraryShardResults.flatMap(_.explanation).sortBy(_.score).lastOption
    val (myHits, friendsHits, othersHits, keepRecords) = LibrarySearch.partition(uniqueHits)
    val LibraryShardResult(hits, show, explanation) = LibrarySearch.merge(myHits, friendsHits, othersHits, maxHits, filter, config, bestExplanation)(keepRecords(_))
    val idFilter = filter.idFilter.toSet ++ hits.map(_.id.id)
    LibrarySearchResult(hits, show, idFilter, searchExperimentId, explanation)
  }

  def distSearchLibraries(shards: Set[Shard[NormalizedURI]], request: LibrarySearchRequest): Future[Seq[LibraryShardResult]] = {
    searchFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig).flatMap {
      case (searchConfig, _) =>
        val debugOption = new DebugOption with Logging
        val debug = request.debug
        if (debug.isDefined) debugOption.debug(debug.get)

        val searchFilter = SearchFilter(request.filter, LibraryContext.None, request.context)
        val searches = searchFactory.getLibrarySearches(shards, request.userId, request.queryString, request.lang1, request.lang2, request.maxHits, request.disablePrefixSearch, searchFilter, searchConfig, request.experiments, request.explain)
        val futureResults: Seq[Future[LibraryShardResult]] = searches.map { librarySearch =>
          if (debug.isDefined) librarySearch.debug(debugOption)
          SafeFuture { librarySearch.execute() }
        }
        Future.sequence(futureResults)
    }
  }
}
