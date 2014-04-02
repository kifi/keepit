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
import com.keepit.search.article.ArticleIndexer
import com.keepit.search.article.StandaloneArticleIndexer
import com.keepit.search.graph.bookmark._
import com.keepit.search.graph.collection._
import com.keepit.search.index.VolatileIndexDirectoryImpl
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.phrasedetector.FakePhraseIndexer
import com.keepit.search.phrasedetector._
import com.keepit.search.spellcheck.SpellCorrector
import com.keepit.search.user.UserIndexer
import com.keepit.search.query.parser.MainQueryParserFactory
import com.keepit.shoebox.{FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient}
import com.keepit.test._
import akka.actor.ActorSystem
import scala.concurrent.duration._
import com.keepit.search.tracker.BrowsingHistoryTracker
import com.keepit.search.tracker.ClickHistoryTracker
import com.keepit.search.tracker.ResultClickTracker
import com.keepit.search.tracker.ProbablisticLRU
import com.keepit.search.tracker.InMemoryResultClickTrackerBuffer
import com.keepit.search.sharding._
import com.keepit.common.aws.AwsModule
import com.keepit.search.graph.user._

trait SearchTestHelper { self: SearchApplicationInjector =>

  val resultClickBuffer  = new InMemoryResultClickTrackerBuffer(1000)
  val resultClickTracker = new ResultClickTracker(new ProbablisticLRU(resultClickBuffer, 8, Int.MaxValue))

  implicit val english = Lang("en")

  def initData(numUsers: Int, numUris: Int) = {
    val users = (0 until numUsers).map {n => User(firstName = "foo" + n, lastName = "")}.toList
    val uris =   (0 until numUris).map {n => NormalizedURI.withHash(title = Some("a" + n),
        normalizedUrl = "http://www.keepit.com/article" + n, state = SCRAPED)}.toList
    val fakeShoeboxClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    (fakeShoeboxClient.saveUsers(users:_*), fakeShoeboxClient.saveURIs(uris:_*))
  }

  def initIndexes(store: ArticleStore)(implicit activeShards: ActiveShards) = {

    val articleIndexers = activeShards.shards.map{ shard =>
      val articleIndexer = new ArticleIndexer(new VolatileIndexDirectoryImpl, store, inject[AirbrakeNotifier])
      (shard -> articleIndexer)
    }
    val shardedArticleIndexer = new ShardedArticleIndexer(articleIndexers.toMap, store, inject[ShoeboxServiceClient])

    val uriGraphIndexers = activeShards.shards.map{ shard =>
      val bookmarkStore = new BookmarkStore(new VolatileIndexDirectoryImpl, inject[AirbrakeNotifier])
      val uriGraphIndexer = new URIGraphIndexer(new VolatileIndexDirectoryImpl, bookmarkStore, inject[AirbrakeNotifier])
      (shard -> uriGraphIndexer)
    }
    val shardedUriGraphIndexer = new ShardedURIGraphIndexer(uriGraphIndexers.toMap, inject[ShoeboxServiceClient])

    val collectionIndexers = activeShards.shards.map{ shard =>
      val collectionNameIndexer = new CollectionNameIndexer(new VolatileIndexDirectoryImpl, inject[AirbrakeNotifier])
      val collectionIndexer = new CollectionIndexer(new VolatileIndexDirectoryImpl, collectionNameIndexer, inject[AirbrakeNotifier])
      (shard -> collectionIndexer)
    }
    val shardedCollectionIndexer = new ShardedCollectionIndexer(collectionIndexers.toMap, inject[ShoeboxServiceClient])

    val userIndexer = new UserIndexer(new VolatileIndexDirectoryImpl, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val userGraphIndexer = new UserGraphIndexer(new VolatileIndexDirectoryImpl, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val searchFriendIndexer = new SearchFriendIndexer(new VolatileIndexDirectoryImpl, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val userGraphsCommander = new UserGraphsCommander(userGraphIndexer, searchFriendIndexer)

    implicit val clock = inject[Clock]
    implicit val fortyTwoServices = inject[FortyTwoServices]

    val mainSearcherFactory = new MainSearcherFactory(
      shardedArticleIndexer,
      userIndexer,
      userGraphsCommander,
      shardedUriGraphIndexer,
      shardedCollectionIndexer,
      new MainQueryParserFactory(new PhraseDetector(new FakePhraseIndexer()), inject[MonitoredAwait]),
      resultClickTracker,
      inject[BrowsingHistoryTracker],
      inject[ClickHistoryTracker],
      inject[ShoeboxServiceClient],
      inject[MonitoredAwait],
      inject[AirbrakeNotifier],
      clock,
      fortyTwoServices)
    (shardedUriGraphIndexer, shardedCollectionIndexer, shardedArticleIndexer, userGraphIndexer, userGraphsCommander, mainSearcherFactory)
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
      canonicalUrl = None,
      alternateUrls = Set.empty,
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
    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    shoebox.clearUserConnections(connections.keys.toSeq:_*)
    shoebox.saveConnections(connections)
  }

  def saveCollections(collections: Collection*): Seq[Collection] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveCollections(collections:_*)
  }

  def saveBookmarksToCollection(collectionId: Id[Collection], bookmarks: Keep*) {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksToCollection(collectionId, bookmarks:_*)
  }

  def saveBookmarks(bookmarks: Keep*): Seq[Keep] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarks(bookmarks:_*)
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false): Seq[Keep] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByURI(edgesByURI, uniqueTitle, isPrivate, source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false): Seq[Keep] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByUser(edgesByUser, uniqueTitle, isPrivate, source)
  }

  def getBookmarks(userId: Id[User]): Seq[Keep] = {
    val future = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].getBookmarks(userId)
     inject[MonitoredAwait].result(future, 3 seconds, "getBookmarks: this should not fail")
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Option[Keep] = {
    val future = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].getBookmarkByUriAndUser(uriId, userId)
    inject[MonitoredAwait].result(future, 3 seconds, "getBookmarkByUriAndUser: this should not fail")
  }

  def getUriIdsInCollection(collectionId: Id[Collection]): Seq[KeepUriAndTime] = {
    val future = inject[ShoeboxServiceClient].getUriIdsInCollection(collectionId)
    inject[MonitoredAwait].result(future, 3 seconds, "getUriIdsInCollection: this should not fail")
  }

  val source = KeepSource("test")
  val defaultConfig = new SearchConfig(SearchConfig.defaultParams)
  val noBoostConfig = defaultConfig.overrideWith(
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
  val allHitsConfig = defaultConfig.overrideWith("tailCutting" -> "0")

  def application = {
    implicit val system = ActorSystem("test")
    new SearchApplication(
      StandaloneTestActorSystemModule(),
      FakeShoeboxServiceModule(),
      new AwsModule()
    )
  }
}
