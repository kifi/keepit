package com.keepit.search.engine

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.model._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import com.keepit.search._
import com.keepit.search.engine.parser.KQueryParser
import com.keepit.search.graph.keep.{ KeepFields, ShardedKeepIndexer }
import com.keepit.search.graph.library.{ LibraryFields, LibraryIndexer }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.phrasedetector.PhraseDetector
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.tracker.ClickHistoryTracker
import com.keepit.search.tracker.ResultClickTracker
import com.keepit.search.graph.user.UserGraphsSearcherFactory
import com.keepit.search.sharding._

@Singleton
class SearchFactory @Inject() (
    shardedArticleIndexer: ShardedArticleIndexer,
    shardedKeepIndexer: ShardedKeepIndexer,
    libraryIndexer: LibraryIndexer,
    userGraphsSearcherFactory: UserGraphsSearcherFactory,
    phraseDetector: PhraseDetector,
    resultClickTracker: ResultClickTracker,
    clickHistoryTracker: ClickHistoryTracker,
    searchConfigManager: SearchConfigManager,
    mainSearcherFactory: MainSearcherFactory,
    monitoredAwait: MonitoredAwait,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices) extends Logging {

  private[this] val phraseDetectionReqConsolidator = new RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]](10 minutes)
  private[this] val libraryIdsReqConsolidator = new RequestConsolidator[Id[User], (Set[Long], Set[Long])](3 seconds)

  def getKifiSearch(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig): Seq[KifiSearch] = {

    val currentTime = System.currentTimeMillis()

    val clickHistoryFuture = mainSearcherFactory.getClickHistoryFuture(userId)
    val clickBoostsFuture = mainSearcherFactory.getClickBoostsFuture(userId, queryString, config.asFloat("maxResultClickBoost"))

    val libraryIdsFuture = getLibraryIdsFuture(userId, filter.libraryId, filter.isLibraryAccessAuthorized)
    val friendIdsFuture = getFriendIdsFuture(userId)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(lang1),
      DefaultAnalyzer.getAnalyzerWithStemmer(lang1),
      lang2.map(DefaultAnalyzer.getAnalyzer),
      lang2.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      config,
      phraseDetector,
      phraseDetectionReqConsolidator,
      monitoredAwait
    )

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()

        // if this is a library restricted search, add a library filter query
        filter.libraryId.map { libId => engBuilder.addFilterQuery(new TermQuery(new Term(KeepFields.libraryField, libId.id.toString))) }

        shards.toSeq.map { shard =>
          val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher

          val timeLogs = new SearchTimeLogs(currentTime)
          timeLogs.queryParsing(parseDoneAt)

          new KifiSearchImpl(
            userId,
            numHitsToReturn,
            filter,
            config,
            engBuilder,
            articleSearcher,
            keepSearcher,
            friendIdsFuture,
            libraryIdsFuture,
            clickBoostsFuture,
            clickHistoryFuture,
            monitoredAwait,
            timeLogs
          )
        }
      case None => Seq.empty[KifiSearch]
    }
  }

  def getFriendIdsFuture(userId: Id[User]): Future[Set[Long]] = userGraphsSearcherFactory(userId).getSearchFriendsFuture()

  def getLibraryIdsFuture(userId: Id[User], library: Option[Id[Library]], libraryAccessAuthorized: Boolean): Future[(Set[Long], Set[Long], Set[Long], Set[Long])] = {

    val trustedPublishedLibIds = library match {
      case Some(libId) => LongArraySet.from(Array(libId.id)) // if this library is not public, it is ignored by the engine
      case None => LongArraySet.empty // we may want to get a set of published libraries that are trusted (or featured) somehow
    }

    val authorizedLibIds = library match {
      case Some(libId) if libraryAccessAuthorized => LongArraySet.from(Array(libId.id))
      case None => LongArraySet.empty
    }

    val future = libraryIdsReqConsolidator(userId) { userId =>
      SafeFuture {
        val searcher = libraryIndexer.getSearcher

        val myOwnLibIds = LongArraySet.from(searcher.findAllIds(new Term(LibraryFields.ownerField, userId.id.toString)).toArray)
        val memberLibIds = LongArraySet.from(searcher.findAllIds(new Term(LibraryFields.usersField, userId.id.toString)).toArray)

        (myOwnLibIds, memberLibIds) // myOwnLibIds is a subset of memberLibIds
      }
    }

    future.map { case (myOwnLibIds, memberLibIds) => (myOwnLibIds, memberLibIds, trustedPublishedLibIds, authorizedLibIds) }(immediate)
  }

  def getKifiNonUserSearch(
    shards: Set[Shard[NormalizedURI]],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig): Seq[KifiSearchNonUserImpl] = {

    val currentTime = System.currentTimeMillis()

    require(filter.libraryId.isEmpty, "library must be specified")

    val libId = filter.libraryId.get

    val libraryIdsFuture = if (filter.isLibraryAccessAuthorized) {
      // this non-user is treated as if he/she were a member of the library
      Future.successful((LongArraySet.empty, LongArraySet.empty, LongArraySet.empty, LongArraySet.from(Array(libId.id))))
    } else {
      // not authorized, but the library may be a published one
      Future.successful((LongArraySet.empty, LongArraySet.empty, LongArraySet.from(Array(libId.id)), LongArraySet.empty))
    }
    val friendIdsFuture = Future.successful(LongArraySet.empty)

    val parser = new KQueryParser(
      DefaultAnalyzer.getAnalyzer(lang1),
      DefaultAnalyzer.getAnalyzerWithStemmer(lang1),
      lang2.map(DefaultAnalyzer.getAnalyzer),
      lang2.map(DefaultAnalyzer.getAnalyzerWithStemmer),
      config,
      phraseDetector,
      phraseDetectionReqConsolidator,
      monitoredAwait
    )

    parser.parse(queryString) match {
      case Some(engBuilder) =>
        val parseDoneAt = System.currentTimeMillis()

        // this is a non-user, library restricted, search, add a library filter query
        engBuilder.addFilterQuery(new TermQuery(new Term(KeepFields.libraryField, libId.id.toString)))

        shards.toSeq.map { shard =>
          val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher

          val timeLogs = new SearchTimeLogs(currentTime)
          timeLogs.queryParsing(parseDoneAt)

          new KifiSearchNonUserImpl(
            libId,
            numHitsToReturn,
            filter,
            config,
            engBuilder,
            articleSearcher,
            keepSearcher,
            friendIdsFuture,
            libraryIdsFuture,
            monitoredAwait,
            timeLogs
          )
        }
      case None => Seq.empty[KifiSearchNonUserImpl]
    }
  }
}
