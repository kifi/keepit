package com.keepit.search

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.{ NormalizedURI, ExperimentType, User }
import com.keepit.search.engine.{ SearchFactory, DebugOption }
import com.keepit.search.engine.user.{ UserSearch, UserShardResult, UserShardHit, UserSearchExplanation }
import com.keepit.search.index.sharding.{ ActiveShards, Sharding, Shard }
import com.keepit.search.user._
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.user._
import com.keepit.typeahead.PrefixFilter
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

case class UserSearchRequest(
  userId: Id[User],
  experiments: Set[ExperimentType],
  queryString: String,
  filter: Option[String],
  context: Option[String],
  lang1: Lang,
  lang2: Option[Lang],
  maxHits: Int,
  disablePrefixSearch: Boolean = false,
  predefinedConfig: Option[SearchConfig],
  debug: Option[String],
  explain: Option[Id[User]])

object UserSearchRequest {
  implicit val format = Json.format[UserSearchRequest]
}

case class UserSearchResult(hits: Seq[UserShardHit], idFilter: Set[Long], searchExperimentId: Option[Id[SearchConfigExperiment]], explanation: Option[UserSearchExplanation])

@ImplementedBy(classOf[UserSearchCommanderImpl])
trait UserSearchCommander {
  def searchUsers(userSearchRequest: DeprecatedUserSearchRequest): DeprecatedUserSearchResult
  def searchUsers(userId: Option[Id[User]], queryText: String, maxHits: Int, context: Option[String], filter: Option[String], excludeSelf: Boolean): DeprecatedUserSearchResult
  def userTypeahead(userSearchRequest: DeprecatedUserSearchRequest, excludedExperiments: Seq[String]): DeprecatedUserSearchResult
  def userTypeahead(userId: Option[Id[User]], queryText: String, pageNum: Int, pageSize: Int, context: Option[String], filter: Option[String], excludedExperiments: Seq[String]): DeprecatedUserSearchResult

  def searchUsers(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    context: Option[String],
    maxHits: Int,
    disablePrefixSearch: Boolean,
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None,
    explain: Option[Id[User]]): Future[UserSearchResult]

  def distSearchUsers(shards: Set[Shard[NormalizedURI]], request: UserSearchRequest): Future[Seq[UserShardResult]]
}

class UserSearchCommanderImpl @Inject() (
    userIndexer: UserIndexer,
    userSearchFilterFactory: DeprecatedUserSearchFilterFactory,
    activeShards: ActiveShards,
    searchFactory: SearchFactory,
    languageCommander: LanguageCommander,
    val searchClient: DistributedSearchServiceClient) extends UserSearchCommander with Sharding with Logging {

  def searchUsers(userSearchRequest: DeprecatedUserSearchRequest): DeprecatedUserSearchResult = {
    val DeprecatedUserSearchRequest(userId, queryText, maxHits, context, filter) = userSearchRequest
    searchUsers(userId, queryText, maxHits, context = Some(context), filter = Some(filter), excludeSelf = false)
  }

  def searchUsers(userId: Option[Id[User]], queryText: String, maxHits: Int, context: Option[String], filter: Option[String], excludeSelf: Boolean): DeprecatedUserSearchResult = {
    val searcher = getUserSearcher
    val parser = new DeprecatedUserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val userFilter = createFilter(userId, filter, context, excludeSelf)
    parser.parse(queryText) match {
      case None => DeprecatedUserSearchResult(Array.empty[DeprecatedUserHit], context.getOrElse(""))
      case Some(q) => searcher.search(q, maxHits, userFilter)
    }
  }

  def userTypeahead(userSearchRequest: DeprecatedUserSearchRequest, excludedExperiments: Seq[String]): DeprecatedUserSearchResult = {
    val DeprecatedUserSearchRequest(userId, queryText, maxHits, context, filter) = userSearchRequest
    userTypeahead(userId, queryText, 0, maxHits, context = Some(context), filter = Some(filter), excludedExperiments = excludedExperiments)
  }

  def userTypeahead(userId: Option[Id[User]], queryText: String, pageNum: Int, pageSize: Int, context: Option[String], filter: Option[String], excludedExperiments: Seq[String]): DeprecatedUserSearchResult = {
    val searchFilter = createFilter(userId, filter, context, true)
    val searcher = getUserSearcher
    val parser = new DeprecatedUserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val queryTerms = PrefixFilter.normalize(queryText).split("\\s+")
    parser.parseWithUserExperimentConstrains(queryText, excludedExperiments) match {
      case None => DeprecatedUserSearchResult(Array.empty[DeprecatedUserHit], context = "")
      case Some(q) => searcher.searchPaging(q, searchFilter, pageNum, pageSize, queryTerms)
    }
  }

  private def getUserSearcher = new DeprecatedUserSearcher(userIndexer.getSearcher)

  private def createFilter(userId: Option[Id[User]], filter: Option[String], context: Option[String], excludeSelf: Boolean) = {
    filter match {
      case Some("f") if userId.isDefined => userSearchFilterFactory.friendsOnly(userId.get, context)
      case Some("nf") if userId.isDefined => userSearchFilterFactory.nonFriendsOnly(userId.get, context)
      case Some("non-f") if userId.isDefined => userSearchFilterFactory.nonFriendsOnly(userId.get, context)
      case _ => userSearchFilterFactory.default(userId, context, excludeSelf) // may change this later
    }
  }

  def searchUsers(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    context: Option[String],
    maxHits: Int,
    disablePrefixSearch: Boolean,
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None,
    explain: Option[Id[User]]): Future[UserSearchResult] = {
    val (localShards, remotePlan) = distributionPlan(userId, activeShards)
    languageCommander.getLangs(localShards, remotePlan, userId, query, acceptLangs, LibraryContext.None).flatMap {
      case (lang1, lang2) =>
        val request = UserSearchRequest(userId, experiments, query, filter, context, lang1, lang2, maxHits, disablePrefixSearch, predefinedConfig, debug, explain)
        val futureRemoteUserShardResults = searchClient.distSearchUsers(remotePlan, request)
        val futureLocalUserShardResult = distSearchUsers(localShards, request)
        val configFuture = searchFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig)
        val futureResults = Future.sequence(futureRemoteUserShardResults :+ futureLocalUserShardResult)
        val searchFilter = SearchFilter(filter.map(Right(_)), LibraryContext.None, context)
        for {
          results <- futureResults
          (config, searchExperimentId) <- configFuture
        } yield toUserSearchResult(results.flatten, maxHits, searchFilter, config, searchExperimentId)
    }
  }

  private def toUserSearchResult(userShardResults: Seq[UserShardResult], maxHits: Int, filter: SearchFilter, config: SearchConfig, searchExperimentId: Option[Id[SearchConfigExperiment]]): UserSearchResult = {
    val uniqueHits = userShardResults.flatMap(_.hits).groupBy(_.id).mapValues(_.maxBy(_.score)).values.toSeq
    val bestExplanation = userShardResults.flatMap(_.explanation).sortBy(_.score).lastOption
    val (myHits, othersHits) = UserSearch.partition(uniqueHits)
    val UserShardResult(hits, explanation) = UserSearch.merge(myHits, othersHits, maxHits, filter, config, bestExplanation)
    val idFilter = filter.idFilter.toSet ++ hits.map(_.id.id)
    UserSearchResult(hits, idFilter, searchExperimentId, explanation)
  }

  def distSearchUsers(shards: Set[Shard[NormalizedURI]], request: UserSearchRequest): Future[Seq[UserShardResult]] = {
    searchFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig).flatMap {
      case (searchConfig, _) =>
        val debugOption = new DebugOption with Logging
        val debug = request.debug
        if (debug.isDefined) debugOption.debug(debug.get)

        val searchFilter = SearchFilter(request.filter.map(Right(_)), LibraryContext.None, request.context)
        val searches = searchFactory.getUserSearches(shards, request.userId, request.queryString, request.lang1, request.lang2, request.maxHits, request.disablePrefixSearch, searchFilter, searchConfig, request.experiments, request.explain)
        val futureResults: Seq[Future[UserShardResult]] = searches.map { userSearch =>
          if (debug.isDefined) userSearch.debug(debugOption)
          SafeFuture { userSearch.execute() }
        }
        Future.sequence(futureResults)
    }
  }
}
