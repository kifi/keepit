package com.keepit.search.engine

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.model._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.search._
import com.keepit.search.engine.parser.KQueryParser
import com.keepit.search.graph.keep.ShardedKeepIndexer
import com.keepit.search.graph.library.{ LibraryFields, LibraryIndexer }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.phrasedetector.PhraseDetector
import org.apache.lucene.index.Term
import scala.collection.mutable.ArrayBuffer
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
    shardedUriGraphIndexer: ShardedURIGraphIndexer,
    shardedCollectionIndexer: ShardedCollectionIndexer,
    phraseDetector: PhraseDetector,
    resultClickTracker: ResultClickTracker,
    clickHistoryTracker: ClickHistoryTracker,
    searchConfigManager: SearchConfigManager,
    mainSearcherFactory: MainSearcherFactory,
    monitoredAwait: MonitoredAwait,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices) extends Logging {

  private[this] val phraseDetectionReqConsolidator = new RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]](10 minutes)
  private[this] val libraryIdsReqConsolidator = new RequestConsolidator[Id[User], (Seq[Long], Seq[Long], Seq[Long])](10 minutes)

  def getKifiSearch(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig): Seq[KifiSearch] = {

    val libraryIdsFuture = getLibraryIdsFuture(userId)
    val clickHistoryFuture = mainSearcherFactory.getClickHistoryFuture(userId)
    val clickBoostsFuture = mainSearcherFactory.getClickBoostsFuture(userId, queryString, config.asFloat("maxResultClickBoost"))

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
        shards.toSeq.map { shard =>
          val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
          val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher
          val eng = engBuilder.build()

          val timeLogs = new SearchTimeLogs()
          timeLogs.queryParsing = parser.totalParseTime

          new KifiSearch(
            userId,
            lang1,
            lang2,
            numHitsToReturn,
            filter,
            config,
            eng,
            articleSearcher,
            keepSearcher,
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

  def getLibraryIdsFuture(userId: Id[User]): Future[(Seq[Long], Seq[Long], Seq[Long])] = libraryIdsReqConsolidator(userId) { userId =>
    userGraphsSearcherFactory(userId).getSearchFriendsFuture().map { friendIds =>
      val searcher = libraryIndexer.getSearcher
      val myLibIds = searcher.findAllIds(new Term(LibraryFields.discoverableOwnerField, userId.id.toString))
      val mySecretLibIds = searcher.findAllIds(new Term(LibraryFields.secretOwnerField, userId.id.toString))

      val friendLibIds = new ArrayBuffer[Long]
      friendIds.foreach { friendId =>
        searcher.findAllIds(new Term(LibraryFields.discoverableOwnerField, friendId.toString), friendLibIds)
      }

      (mySecretLibIds, myLibIds, friendLibIds)
    }
  }
}
