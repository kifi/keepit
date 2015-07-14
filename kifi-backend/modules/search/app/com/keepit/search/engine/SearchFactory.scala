package com.keepit.search.engine

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.model._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import com.keepit.search._
import com.keepit.search.engine.library.LibrarySearch
import com.keepit.search.engine.parser.KQueryParser
import com.keepit.search.engine.uri.{ UriSearch, UriSearchImpl, UriSearchNonUserImpl }
import com.keepit.search.engine.user.UserSearch
import com.keepit.search.index.article.ShardedArticleIndexer
import com.keepit.search.index.graph.keep.{ KeepFields, ShardedKeepIndexer }
import com.keepit.search.index.graph.library.membership.{ LibraryMembershipIndexer, LibraryMembershipIndexable }
import com.keepit.search.index.graph.library.{ LibraryFields, LibraryIndexable, LibraryIndexer }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.graph.organization.{ OrganizationMembershipIndexable, OrganizationMembershipIndexer }
import com.keepit.search.index.phrase.PhraseDetector
import com.keepit.search.index.user.UserIndexer
import com.keepit.search.util.LongArraySet
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.tracking.ClickHistoryTracker
import com.keepit.search.tracking.ResultClickTracker
import com.keepit.search.index.graph.user.UserGraphsSearcherFactory
import com.keepit.search.index.sharding._
import com.keepit.common.core._

@Singleton
class SearchFactory @Inject() (
    shardedArticleIndexer: ShardedArticleIndexer,
    shardedKeepIndexer: ShardedKeepIndexer,
    libraryIndexer: LibraryIndexer,
    libraryMembershipIndexer: LibraryMembershipIndexer,
    orgMemembershipIndexer: OrganizationMembershipIndexer,
    userIndexer: UserIndexer,
    userGraphsSearcherFactory: UserGraphsSearcherFactory,
    shoeboxClient: ShoeboxServiceClient,
    phraseDetector: PhraseDetector,
    resultClickTracker: ResultClickTracker,
    clickHistoryTracker: ClickHistoryTracker,
    searchConfigManager: SearchConfigManager,
    monitoredAwait: MonitoredAwait,
    libraryQualityEvaluator: LibraryQualityEvaluator,
    implicit private val fortyTwoServices: FortyTwoServices) extends Logging {

  lazy val searchServiceStartedAt: Long = fortyTwoServices.started.getMillis()

  private[this] val phraseDetectionReqConsolidator = new RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]](10 minutes)
  private[this] val libraryIdsReqConsolidator = new RequestConsolidator[Id[User], (Set[Long], Set[Long])](3 seconds)
  private[this] val configReqConsolidator = new RequestConsolidator[(Id[User]), (SearchConfig, Option[Id[SearchConfigExperiment]])](10 seconds)
  private[this] val fakeUserIdsReqConsolidator = new RequestConsolidator[this.type, Set[Long]](3 seconds)
  private[this] val orgIdsReqConsolidater = new RequestConsolidator[Id[User], Set[Long]](3 seconds)

  def getUriSearches(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    firstLang: Lang,
    secondLang: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    orderBy: SearchRanking,
    config: SearchConfig,
    experiments: Set[ExperimentType]): Seq[UriSearch] = {

    val currentTime = System.currentTimeMillis()

    val clickBoostsFuture = resultClickTracker.getBoostsFuture(userId, queryString, config.asFloat("maxResultClickBoost"))
    val clickHistoryFuture = clickHistoryTracker.getClickHistoryFuture(userId)

    val libraryIdsFuture = getLibraryIdsFuture(userId, filter.library)
    val friendIdsFuture = getSearchFriends(userId)
    val restrictedUserIdsFuture = getRestrictedUsers(Some(userId))
    val orgIdsFuture = getOrganizations(userId)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(firstLang),
      DefaultAnalyzer.getAnalyzerWithStemmer(firstLang),
      secondLang.map(DefaultAnalyzer.getAnalyzer),
      secondLang.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      false,
      config,
      phraseDetector,
      phraseDetectionReqConsolidator)

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()

        // set ranking method (default: relevancy)

        engBuilder.setRanking(orderBy)

        // if this is a library restricted search, add a library filter query
        filter.library.foreach(addLibraryFilterToUriSearch(engBuilder, _))

        // if this is a user restricted search, add a user filter query
        filter.user.foreach(addUserFilterToUriSearch(engBuilder, _))

        val librarySearcher = libraryIndexer.getSearcher

        shards.toSeq.map { shard =>
          val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher

          val timeLogs = new SearchTimeLogs(currentTime)
          timeLogs.queryParsing(parseDoneAt)

          new UriSearchImpl(
            userId,
            numHitsToReturn,
            filter,
            config,
            engBuilder,
            articleSearcher,
            keepSearcher,
            librarySearcher,
            friendIdsFuture,
            restrictedUserIdsFuture,
            libraryIdsFuture,
            orgIdsFuture,
            clickBoostsFuture,
            clickHistoryFuture,
            monitoredAwait,
            timeLogs,
            (firstLang, secondLang)
          )
        }
      case None => Seq.empty[UriSearch]
    }
  }

  def getSearchFriends(userId: Id[User]): Future[Set[Long]] = userGraphsSearcherFactory(userId).getSearchFriendsFuture()

  def getRestrictedUsers(userId: Option[Id[User]]): Future[Set[Long]] = {
    val futureFakeUserIds = fakeUserIdsReqConsolidator(this) { _ => shoeboxClient.getAllFakeUsers().imap(_.map(_.id)) }
    val futureIsFakeOrAdmin = userId match {
      case None => Future.successful(false)
      case Some(id) => shoeboxClient.getUserExperiments(id).imap(experiments => experiments.contains(ExperimentType.ADMIN) || experiments.contains(ExperimentType.FAKE))
    }
    for {
      isFakeOrAdmin <- futureIsFakeOrAdmin
      fakeUserIds <- futureFakeUserIds // no performance optimization for admins on purpose
    } yield {
      if (isFakeOrAdmin) Set.empty[Long] else fakeUserIds
    }
  }

  def getFriends(userId: Id[User]): Future[Set[Long]] = userGraphsSearcherFactory(userId).getConnectedUsersFuture()

  def getMutualFriends(userId: Id[User], otherUserIds: Set[Id[User]]): Future[Map[Id[User], Set[Id[User]]]] = {
    getFriends(userId).flatMap { userFriends =>
      val futureMutualFriendsByUserId = otherUserIds.map { otherUserId =>
        getFriends(otherUserId).imap { otherUserFriends =>
          otherUserId -> (userFriends intersect otherUserFriends).map(Id[User](_))
        }
      }
      Future.sequence(futureMutualFriendsByUserId).map(_.toMap)
    }
  }

  def getOrganizations(userId: Id[User]): Future[Set[Long]] = {
    orgIdsReqConsolidater(userId) { userId =>
      SafeFuture {
        val searcher = orgMemembershipIndexer.getSearcher
        OrganizationMembershipIndexable.getOrgsByMember(searcher, userId)
      }
    }
  }

  def getLibraryIdsFuture(userId: Id[User], libraryScope: Option[LibraryScope]): Future[(Set[Long], Set[Long], Set[Long], Set[Long])] = {

    val trustedPublishedLibIds = {
      val librarySearcher = libraryIndexer.getSearcher
      libraryScope match {
        case Some(library) if !library.authorized && LibraryIndexable.isPublished(librarySearcher, library.id.id) => LongArraySet.from(Array(library.id.id))
        case _ => LongArraySet.empty // we may want to get a set of published libraries that are trusted (or featured) somehow
      }
    }

    val authorizedLibIds = libraryScope match {
      case Some(library) if library.authorized => LongArraySet.from(Array(library.id.id))
      case _ => LongArraySet.empty
    }

    val future = libraryIdsReqConsolidator(userId) { userId =>
      SafeFuture {
        val libraryMembershipSearcher = libraryMembershipIndexer.getSearcher
        val myOwnLibIds = LibraryMembershipIndexable.getLibrariesByCollaborator(libraryMembershipSearcher, userId)
        val memberLibIds = LibraryMembershipIndexable.getLibrariesByMember(libraryMembershipSearcher, userId)

        (myOwnLibIds, memberLibIds) // myOwnLibIds is a subset of memberLibIds
      }
    }

    future.map { case (myOwnLibIds, memberLibIds) => (myOwnLibIds, memberLibIds, trustedPublishedLibIds, authorizedLibIds) }(immediate)
  }

  def getNonUserUriSearches(
    shards: Set[Shard[NormalizedURI]],
    queryString: String,
    firstLang: Lang,
    secondLang: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    orderBy: SearchRanking,
    config: SearchConfig): Seq[UriSearchNonUserImpl] = {

    val currentTime = System.currentTimeMillis()

    val libraryIdsFuture = filter.library match {
      case Some(library) if library.authorized => // this non-user is treated as if he/she were a member of the library
        Future.successful((LongArraySet.empty, LongArraySet.empty, LongArraySet.empty, LongArraySet.from(Array(library.id.id))))
      case Some(library) if !library.authorized => // not authorized, but the library may be a published one
        Future.successful((LongArraySet.empty, LongArraySet.empty, LongArraySet.from(Array(library.id.id)), LongArraySet.empty))
      case _ =>
        throw new IllegalArgumentException("library must be specified")
    }
    val friendIdsFuture = Future.successful(LongArraySet.empty)
    val orgIdsFuture = Future.successful(LongArraySet.empty)

    val restrictedUserIdsFuture = getRestrictedUsers(None)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(firstLang),
      DefaultAnalyzer.getAnalyzerWithStemmer(firstLang),
      secondLang.map(DefaultAnalyzer.getAnalyzer),
      secondLang.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      false,
      config,
      phraseDetector,
      phraseDetectionReqConsolidator)

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()

        val librarySearcher = libraryIndexer.getSearcher

        // set ranking method (default: relevancy)

        engBuilder.setRanking(orderBy)

        // this is a non-user, library restricted search, add a library filter query
        addLibraryFilterToUriSearch(engBuilder, filter.library.get)

        shards.toSeq.map { shard =>
          val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher

          val timeLogs = new SearchTimeLogs(currentTime)
          timeLogs.queryParsing(parseDoneAt)

          new UriSearchNonUserImpl(
            numHitsToReturn,
            filter,
            config,
            engBuilder,
            articleSearcher,
            keepSearcher,
            librarySearcher,
            friendIdsFuture,
            restrictedUserIdsFuture,
            libraryIdsFuture,
            orgIdsFuture,
            monitoredAwait,
            timeLogs,
            (firstLang, secondLang)
          )
        }
      case None => Seq.empty[UriSearchNonUserImpl]
    }
  }

  private def addLibraryFilterToUriSearch(engBuilder: QueryEngineBuilder, library: LibraryScope) = { engBuilder.addFilterQuery(new TermQuery(new Term(KeepFields.libraryField, library.id.id.toString))) }
  private def addUserFilterToUriSearch(engBuilder: QueryEngineBuilder, user: UserScope) = { engBuilder.addFilterQuery(new TermQuery(new Term(KeepFields.userField, user.id.id.toString))) }

  def getLibrarySearches(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    firstLang: Lang,
    secondLang: Option[Lang],
    numHitsToReturn: Int,
    disablePrefixSearch: Boolean,
    filter: SearchFilter,
    config: SearchConfig,
    experiments: Set[ExperimentType],
    explain: Option[Id[Library]]): Seq[LibrarySearch] = {

    val currentTime = System.currentTimeMillis()

    val libraryIdsFuture = getLibraryIdsFuture(userId, filter.library)
    val friendIdsFuture = getSearchFriends(userId)
    val restrictedUserIdsFuture = getRestrictedUsers(Some(userId))
    val orgIdsFuture = getOrganizations(userId)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(firstLang),
      DefaultAnalyzer.getAnalyzerWithStemmer(firstLang),
      secondLang.map(DefaultAnalyzer.getAnalyzer),
      secondLang.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      disablePrefixSearch,
      config,
      phraseDetector,
      phraseDetectionReqConsolidator
    )

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()

        // if this is a user restricted search, add a user filter queries
        filter.user.foreach { user =>
          addUserFilterToLibrarySearch(engBuilder, user)
          addUserFilterToUriSearch(engBuilder, user)
        }

        val librarySearcher = libraryIndexer.getSearcher
        val libraryMembershipSearcher = libraryMembershipIndexer.getSearcher
        val userSearcher = userIndexer.getSearcher
        shards.toSeq.map { shard =>
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher

          val timeLogs = new SearchTimeLogs(currentTime)
          timeLogs.queryParsing(parseDoneAt)

          new LibrarySearch(
            userId,
            numHitsToReturn,
            filter,
            config,
            engBuilder,
            librarySearcher,
            libraryMembershipSearcher,
            keepSearcher,
            userSearcher,
            libraryQualityEvaluator,
            friendIdsFuture,
            restrictedUserIdsFuture,
            libraryIdsFuture,
            orgIdsFuture,
            monitoredAwait,
            timeLogs,
            explain.map((_, firstLang, secondLang))
          )
        }
      case None => Seq.empty
    }
  }

  private def addUserFilterToLibrarySearch(engBuilder: QueryEngineBuilder, user: UserScope) = { engBuilder.addFilterQuery(new TermQuery(new Term(LibraryFields.ownerField, user.id.id.toString))) }

  def getUserSearches(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    firstLang: Lang,
    secondLang: Option[Lang],
    numHitsToReturn: Int,
    disablePrefixSearch: Boolean,
    filter: SearchFilter,
    config: SearchConfig,
    experiments: Set[ExperimentType],
    explain: Option[Id[User]]): Seq[UserSearch] = {

    val currentTime = System.currentTimeMillis()

    val libraryIdsFuture = getLibraryIdsFuture(userId, filter.library)
    val friendIdsFuture = getSearchFriends(userId)
    val restrictedUserIdsFuture = getRestrictedUsers(Some(userId))
    val orgIdsFuture = getOrganizations(userId)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(firstLang),
      DefaultAnalyzer.getAnalyzerWithStemmer(firstLang),
      secondLang.map(DefaultAnalyzer.getAnalyzer),
      secondLang.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      disablePrefixSearch,
      config,
      phraseDetector,
      phraseDetectionReqConsolidator
    )

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()
        val librarySearcher = libraryIndexer.getSearcher
        val userSearcher = userIndexer.getSearcher
        shards.toSeq.map { shard =>
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher

          val timeLogs = new SearchTimeLogs(currentTime)
          timeLogs.queryParsing(parseDoneAt)

          new UserSearch(
            userId,
            numHitsToReturn,
            filter,
            config,
            engBuilder,
            librarySearcher,
            keepSearcher,
            userSearcher,
            libraryQualityEvaluator,
            friendIdsFuture,
            restrictedUserIdsFuture,
            libraryIdsFuture,
            orgIdsFuture,
            monitoredAwait,
            timeLogs,
            explain.map((_, firstLang, secondLang))
          )
        }
      case None => Seq.empty
    }
  }

  def getConfigFuture(userId: Id[User], experiments: Set[ExperimentType], predefinedConfig: Option[SearchConfig] = None): Future[(SearchConfig, Option[Id[SearchConfigExperiment]])] = {
    predefinedConfig match {
      case None =>
        configReqConsolidator(userId) { k => searchConfigManager.getConfigFuture(userId, experiments) }
      case Some(conf) =>
        val default = searchConfigManager.defaultConfig
        // almost complete overwrite. But when search config parameter list changes, this prevents exception
        Future.successful((new SearchConfig(default.params ++ conf.params), None))
    }
  }

  def warmUp(userId: Id[User]): Seq[Future[Any]] = {
    Seq(clickHistoryTracker.getClickHistoryFuture(userId)) // returning futures to pin them in the heap
  }
}
