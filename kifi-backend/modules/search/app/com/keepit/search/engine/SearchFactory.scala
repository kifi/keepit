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
import com.keepit.search.index.graph.keep.{ KeepFields, ShardedKeepIndexer }
import com.keepit.search.index.graph.library.{ LibraryIndexable, LibraryFields, LibraryIndexer }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.phrase.PhraseDetector
import com.keepit.search.index.user.UserIndexer
import com.keepit.search.util.LongArraySet
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
    userIndexer: UserIndexer,
    userGraphsSearcherFactory: UserGraphsSearcherFactory,
    phraseDetector: PhraseDetector,
    resultClickTracker: ResultClickTracker,
    clickHistoryTracker: ClickHistoryTracker,
    searchConfigManager: SearchConfigManager,
    monitoredAwait: MonitoredAwait,
    implicit private val fortyTwoServices: FortyTwoServices) extends Logging {

  lazy val searchServiceStartedAt: Long = fortyTwoServices.started.getMillis()

  private[this] val phraseDetectionReqConsolidator = new RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]](10 minutes)
  private[this] val libraryIdsReqConsolidator = new RequestConsolidator[Id[User], (Set[Long], Set[Long])](3 seconds)
  private[this] val configReqConsolidator = new RequestConsolidator[(Id[User]), (SearchConfig, Option[Id[SearchConfigExperiment]])](10 seconds)

  def getUriSearches(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig): Seq[UriSearch] = {

    val currentTime = System.currentTimeMillis()

    val clickBoostsFuture = resultClickTracker.getBoostsFuture(userId, queryString, config.asFloat("maxResultClickBoost"))
    val clickHistoryFuture = clickHistoryTracker.getClickHistoryFuture(userId)

    val libraryIdsFuture = getLibraryIdsFuture(userId, filter.libraryContext)
    val friendIdsFuture = getSearchFriends(userId)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(lang1),
      DefaultAnalyzer.getAnalyzerWithStemmer(lang1),
      lang2.map(DefaultAnalyzer.getAnalyzer),
      lang2.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      config,
      phraseDetector,
      phraseDetectionReqConsolidator)

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()

        // if this is a library restricted search, add a library filter query
        filter.libraryContext match {
          case LibraryContext.Authorized(libId) => addLibraryFilter(engBuilder, libId)
          case LibraryContext.NotAuthorized(libId) => addLibraryFilter(engBuilder, libId)
          case _ =>
        }

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
            libraryIdsFuture,
            clickBoostsFuture,
            clickHistoryFuture,
            monitoredAwait,
            timeLogs,
            (lang1, lang2)
          )
        }
      case None => Seq.empty[UriSearch]
    }
  }

  def getSearchFriends(userId: Id[User]): Future[Set[Long]] = userGraphsSearcherFactory(userId).getSearchFriendsFuture()

  def getMutualFriends(userId: Id[User], otherUserIds: Set[Id[User]]): Future[Map[Id[User], Set[Id[User]]]] = {
    def getFriends(userId: Id[User]): Future[Set[Long]] = userGraphsSearcherFactory(userId).getConnectedUsersFuture()
    getFriends(userId).flatMap { userFriends =>
      val futureMutualFriendsByUserId = otherUserIds.map { otherUserId =>
        getFriends(otherUserId).imap { otherUserFriends =>
          otherUserId -> (userFriends intersect otherUserFriends).map(Id[User](_))
        }
      }
      Future.sequence(futureMutualFriendsByUserId).map(_.toMap)
    }
  }

  def getLibraryIdsFuture(userId: Id[User], library: LibraryContext): Future[(Set[Long], Set[Long], Set[Long], Set[Long])] = {
    val librarySearcher = libraryIndexer.getSearcher

    val trustedPublishedLibIds = library match {
      case LibraryContext.NotAuthorized(libId) if LibraryIndexable.isPublished(librarySearcher, libId) => LongArraySet.from(Array(libId))
      case _ => LongArraySet.empty // we may want to get a set of published libraries that are trusted (or featured) somehow
    }

    val authorizedLibIds = library match {
      case LibraryContext.Authorized(libId) => LongArraySet.from(Array(libId))
      case _ => LongArraySet.empty
    }

    val future = libraryIdsReqConsolidator(userId) { userId =>
      SafeFuture {
        val myOwnLibIds = LibraryIndexable.getLibrariesByOwner(librarySearcher, userId)
        val memberLibIds = LibraryIndexable.getLibrariesByMember(librarySearcher, userId)

        (myOwnLibIds, memberLibIds) // myOwnLibIds is a subset of memberLibIds
      }
    }

    future.map { case (myOwnLibIds, memberLibIds) => (myOwnLibIds, memberLibIds, trustedPublishedLibIds, authorizedLibIds) }(immediate)
  }

  def getNonUserUriSearches(
    shards: Set[Shard[NormalizedURI]],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig): Seq[UriSearchNonUserImpl] = {

    val currentTime = System.currentTimeMillis()

    val libraryIdsFuture = filter.libraryContext match {
      case LibraryContext.Authorized(libId) => // this non-user is treated as if he/she were a member of the library
        Future.successful((LongArraySet.empty, LongArraySet.empty, LongArraySet.empty, LongArraySet.from(Array(libId))))
      case LibraryContext.NotAuthorized(libId) => // not authorized, but the library may be a published one
        Future.successful((LongArraySet.empty, LongArraySet.empty, LongArraySet.from(Array(libId)), LongArraySet.empty))
      case _ =>
        throw new IllegalArgumentException("library must be specified")
    }
    val friendIdsFuture = Future.successful(LongArraySet.empty)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(lang1),
      DefaultAnalyzer.getAnalyzerWithStemmer(lang1),
      lang2.map(DefaultAnalyzer.getAnalyzer),
      lang2.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      config,
      phraseDetector,
      phraseDetectionReqConsolidator)

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()

        val librarySearcher = libraryIndexer.getSearcher

        // this is a non-user, library restricted search, add a library filter query
        addLibraryFilter(engBuilder, filter.libraryContext.get)

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
            libraryIdsFuture,
            monitoredAwait,
            timeLogs,
            (lang1, lang2)
          )
        }
      case None => Seq.empty[UriSearchNonUserImpl]
    }
  }

  private def addLibraryFilter(engBuilder: QueryEngineBuilder, libId: Long) = { engBuilder.addFilterQuery(new TermQuery(new Term(KeepFields.libraryField, libId.toString))) }

  def getLibrarySearches(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    explain: Option[Id[Library]]): Seq[LibrarySearch] = {

    val currentTime = System.currentTimeMillis()

    val libraryIdsFuture = getLibraryIdsFuture(userId, filter.libraryContext)
    val friendIdsFuture = getSearchFriends(userId)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(lang1),
      DefaultAnalyzer.getAnalyzerWithStemmer(lang1),
      lang2.map(DefaultAnalyzer.getAnalyzer),
      lang2.map(DefaultAnalyzer.getAnalyzerWithStemmer),
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

          new LibrarySearch(
            userId,
            numHitsToReturn,
            filter,
            config,
            engBuilder,
            librarySearcher,
            keepSearcher,
            userSearcher,
            friendIdsFuture,
            libraryIdsFuture,
            monitoredAwait,
            timeLogs,
            explain.map((_, lang1, lang2))
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
