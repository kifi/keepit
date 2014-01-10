package com.keepit.search

import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.User
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.bookmark._
import com.keepit.search.index.VolatileIndexDirectoryImpl
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.phrasedetector.FakePhraseIndexer
import com.keepit.search.article.ArticleIndexer
import com.keepit.search.phrasedetector._
import com.keepit.search.spellcheck.SpellCorrector
import com.keepit.search.graph.{URIGraphImpl}
import com.keepit.search.graph.collection._
import com.keepit.search.user.UserIndexer
import com.keepit.search.query.parser.MainQueryParserFactory
import com.keepit.shoebox.{FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient}
import com.keepit.test._
import akka.actor.ActorSystem
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import scala.concurrent.duration._
import com.keepit.search.tracker.BrowsingHistoryTracker
import com.keepit.search.tracker.ClickHistoryTracker
import com.keepit.search.tracker.ResultClickTracker
import com.keepit.search.tracker.ProbablisticLRU
import com.keepit.search.tracker.InMemoryResultClickTrackerBuffer
import com.keepit.search.sharding.Shard
import com.keepit.search.sharding.ShardedArticleIndexer
import com.keepit.search.sharding.ActiveShards

trait SearchTestHepler { self: SearchApplicationInjector =>

  val singleShard = Shard(0,1)
  val activeShards = ActiveShards(Seq(singleShard))
  val resultClickBuffer  = new InMemoryResultClickTrackerBuffer(1000)
  val resultClickTracker = new ResultClickTracker(new ProbablisticLRU(resultClickBuffer, 8, Int.MaxValue)(None))

  implicit val english = Lang("en")

  def initData(numUsers: Int, numUris: Int) = {
    val users = (0 until numUsers).map {n => User(firstName = "foo" + n, lastName = "")}.toList
    val uris =   (0 until numUris).map {n => NormalizedURI.withHash(title = Some("a" + n),
        normalizedUrl = "http://www.keepit.com/article" + n, state = SCRAPED)}.toList
    val fakeShoeboxClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    (fakeShoeboxClient.saveUsers(users:_*), fakeShoeboxClient.saveURIs(uris:_*))
  }

  def initIndexes(store: ArticleStore) = {
    val articleConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val bookmarkStoreConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val graphConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val collectConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val colNameConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)

    val articleIndexer = new ArticleIndexer(new VolatileIndexDirectoryImpl, articleConfig, store, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val shardedArticleIndexer = new ShardedArticleIndexer(
        Map(singleShard -> articleIndexer),
        store,
        inject[ShoeboxServiceClient]
    )
    val bookmarkStore = new BookmarkStore(new VolatileIndexDirectoryImpl, bookmarkStoreConfig, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val userIndexer = new UserIndexer(new VolatileIndexDirectoryImpl, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val collectionNameIndexer = new CollectionNameIndexer(new VolatileIndexDirectoryImpl, colNameConfig, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val uriGraph = new URIGraphImpl(
      new URIGraphIndexer(new VolatileIndexDirectoryImpl, graphConfig, bookmarkStore, inject[AirbrakeNotifier], inject[ShoeboxServiceClient]),
      new CollectionIndexer(new VolatileIndexDirectoryImpl, collectConfig, collectionNameIndexer, inject[AirbrakeNotifier], inject[ShoeboxServiceClient]),
      inject[ShoeboxServiceClient],
      inject[MonitoredAwait])
    implicit val clock = inject[Clock]
    implicit val fortyTwoServices = inject[FortyTwoServices]

    val mainSearcherFactory = new MainSearcherFactory(
      shardedArticleIndexer,
      userIndexer,
      uriGraph,
      new MainQueryParserFactory(new PhraseDetector(new FakePhraseIndexer()), inject[MonitoredAwait]),
      resultClickTracker,
      inject[BrowsingHistoryTracker],
      inject[ClickHistoryTracker],
      inject[ShoeboxServiceClient],
      inject[SpellCorrector],
      inject[MonitoredAwait],
      inject[AirbrakeNotifier],
      clock,
      fortyTwoServices)
    (uriGraph, articleIndexer, mainSearcherFactory)
  }

  def mkStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
      store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs documents".format(idx)))
      store
    }
  }

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
    Article(
      id = normalizedUriId,
      title = title,
      description = None,
      keywords = None,
      media = None,
      content = content,
      scrapedAt = currentDateTime,
      httpContentType = Some("text/html"),
      httpOriginalContentCharset = Option("UTF-8"),
      state = SCRAPED,
      message = None,
      titleLang = Some(english),
      contentLang = Some(english))
  }

  def setConnections(connections: Map[Id[User], Set[Id[User]]]) {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].clearUserConnections(connections.keys.toSeq:_*)
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveConnections(connections)
  }

  def saveCollections(collections: Collection*): Seq[Collection] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveCollections(collections:_*)
  }

  def saveBookmarksToCollection(collectionId: Id[Collection], bookmarks: Bookmark*) {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksToCollection(collectionId, bookmarks:_*)
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false): Seq[Bookmark] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByURI(edgesByURI, uniqueTitle, isPrivate, source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false): Seq[Bookmark] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByUser(edgesByUser, uniqueTitle, isPrivate, source)
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Option[Bookmark] = {
    val future = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].getBookmarkByUriAndUser(uriId, userId)
    inject[MonitoredAwait].result(future, 3 seconds, "getBookmarkByUriAndUser: this should not fail")
  }

  val source = BookmarkSource("test")
  val defaultConfig = new SearchConfig(SearchConfig.defaultParams)
  val noBoostConfig = defaultConfig(
    "myBookmarkBoost" -> "1",
    "sharingBoostInNetwork" -> "0",
    "sharingBoostOutOfNetwork" -> "0",
    "recencyBoost" -> "0",
    "newContentBoost" -> "0",
    "proximityBoost" -> "0",
    "semanticBoost" -> "0",
    "percentMatch" -> "0",
    "tailCutting" -> "0",
    "dampingByRank" -> "false")
  val allHitsConfig = defaultConfig("tailCutting" -> "0")

  def application = {
    implicit val system = ActorSystem("test")
    new SearchApplication(
      StandaloneTestActorSystemModule(),
      FakeShoeboxServiceModule()
    )
  }
}
