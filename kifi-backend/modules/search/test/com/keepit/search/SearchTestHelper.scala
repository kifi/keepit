package com.keepit.search

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.User
import com.keepit.search.index.article.ArticleIndexer
import com.keepit.search.engine.{ LibraryQualityEvaluator, SearchFactory }
import com.keepit.search.index.graph.collection._
import com.keepit.search.index.graph.keep.{ ShardedKeepIndexer, KeepIndexer }
import com.keepit.search.index.graph.library.LibraryIndexer
import com.keepit.search.index.VolatileIndexDirectory
import com.keepit.search.index.graph.library.membership.LibraryMembershipIndexer
import com.keepit.search.index.phrase._
import com.keepit.search.index.user.UserIndexer
import com.keepit.search.test.SearchTestInjector
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import scala.concurrent.duration._
import com.keepit.search.tracking.ClickHistoryTracker
import com.keepit.search.tracking.ResultClickTracker
import com.keepit.search.tracking.ProbablisticLRU
import com.keepit.search.tracking.InMemoryResultClickTrackerBuffer
import com.keepit.search.index.sharding._
import com.keepit.common.aws.AwsModule
import com.keepit.search.index.graph.user._
import com.google.inject.Injector
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.util.PlayAppConfigurationModule

trait SearchTestHelper { self: SearchTestInjector =>

  val resultClickBuffer = new InMemoryResultClickTrackerBuffer(1000)
  val resultClickTracker = new ResultClickTracker(new ProbablisticLRU(resultClickBuffer, 8, Int.MaxValue))

  implicit val english = Lang("en")

  def initData(numUsers: Int, numUris: Int)(implicit injector: Injector) = {
    val users = (0 until numUsers).map { n => User(firstName = "foo" + n, lastName = "", username = Username("test" + n), normalizedUsername = "test" + n) }.toList
    val uris = (0 until numUris).map { n =>
      NormalizedURI.withHash(title = Some("a" + n),
        normalizedUrl = "http://www.keepit.com/article" + n, state = SCRAPED)
    }.toList
    val fakeShoeboxClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    (fakeShoeboxClient.saveUsers(users: _*), fakeShoeboxClient.saveURIs(uris: _*))
  }

  def initIndexes(store: ArticleStore)(implicit activeShards: ActiveShards, injector: Injector) = {
    val articleIndexers = activeShards.local.map { shard =>
      val articleIndexer = new ArticleIndexer(new VolatileIndexDirectory, store, inject[AirbrakeNotifier])
      (shard -> articleIndexer)
    }
    val shardedArticleIndexer = new ShardedArticleIndexer(articleIndexers.toMap, store, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])

    val keepIndexers = activeShards.local.map { shard =>
      val keepIndexer = new KeepIndexer(new VolatileIndexDirectory, shard, inject[AirbrakeNotifier])
      (shard -> keepIndexer)
    }
    val shardedKeepIndexer = new ShardedKeepIndexer(keepIndexers.toMap, inject[ShoeboxServiceClient], inject[AirbrakeNotifier])

    val collectionIndexers = activeShards.local.map { shard =>
      val collectionIndexer = new CollectionIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier])
      (shard -> collectionIndexer)
    }
    val shardedCollectionIndexer = new ShardedCollectionIndexer(collectionIndexers.toMap, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])

    val userIndexer = new UserIndexer(new VolatileIndexDirectory, inject[ShoeboxServiceClient], inject[AirbrakeNotifier])
    val userGraphIndexer = new UserGraphIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val searchFriendIndexer = new SearchFriendIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val userGraphsSearcherFactory = new UserGraphsSearcherFactory(userGraphIndexer, searchFriendIndexer)

    val libraryIndexer = new LibraryIndexer(new VolatileIndexDirectory, inject[ShoeboxServiceClient], inject[AirbrakeNotifier])
    val libraryMembershipIndexer = new LibraryMembershipIndexer(new VolatileIndexDirectory, inject[ShoeboxServiceClient], inject[AirbrakeNotifier])
    val phraseDetector = new PhraseDetector(new FakePhraseIndexer(inject[AirbrakeNotifier]))
    val libraryQualityEvaluator = new LibraryQualityEvaluator(activeShards)

    implicit val clock = inject[Clock]
    implicit val fortyTwoServices = inject[FortyTwoServices]

    val searchFactory = new SearchFactory(
      shardedArticleIndexer,
      shardedKeepIndexer,
      libraryIndexer,
      libraryMembershipIndexer,
      userIndexer,
      userGraphsSearcherFactory,
      inject[ShoeboxServiceClient],
      phraseDetector,
      resultClickTracker,
      inject[ClickHistoryTracker],
      inject[SearchConfigManager],
      inject[MonitoredAwait],
      libraryQualityEvaluator,
      fortyTwoServices)

    (shardedCollectionIndexer, shardedArticleIndexer, userGraphIndexer, userGraphsSearcherFactory, searchFactory, shardedKeepIndexer, libraryIndexer, libraryMembershipIndexer)
  }

  def mkStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new InMemoryArticleStoreImpl()) {
      case (store, (uri, idx)) =>
        store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs documents".format(idx)))
        store
    }
  }

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
    Article(
      id = normalizedUriId,
      title = title,
      description = None,
      author = None,
      publishedAt = None,
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

  def setConnections(connections: Map[Id[User], Set[Id[User]]])(implicit injector: Injector) {
    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    shoebox.clearUserConnections(connections.keys.toSeq: _*)
    shoebox.saveConnections(connections)
  }

  def saveCollections(collections: Collection*)(implicit injector: Injector): Seq[Collection] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveCollections(collections: _*)
  }

  def saveBookmarksToCollection(collectionId: Id[Collection], bookmarks: Keep*)(implicit injector: Injector) {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksToCollection(collectionId, bookmarks: _*)
  }

  def saveBookmarks(bookmarks: Keep*)(implicit injector: Injector): Seq[Keep] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarks(bookmarks: _*)
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false)(implicit injector: Injector): Seq[Keep] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByURI(edgesByURI, uniqueTitle, isPrivate, source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false)(implicit injector: Injector): Seq[Keep] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByUser(edgesByUser, uniqueTitle, isPrivate, source)
  }

  def getBookmarks(userId: Id[User])(implicit injector: Injector): Seq[Keep] = {
    val future = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].getBookmarks(userId)
    inject[MonitoredAwait].result(future, 3 seconds, "getBookmarks: this should not fail")
  }

  def getUriIdsInCollection(collectionId: Id[Collection])(implicit injector: Injector): Seq[KeepUriAndTime] = {
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
    "percentMatch" -> "0",
    "tailCutting" -> "0",
    "dampingByRank" -> "false")
  val allHitsConfig = defaultConfig.overrideWith("tailCutting" -> "0")

  // implicit val system = ActorSystem("test")
  val helperModules = Seq(FakeActorSystemModule(), FakeShoeboxServiceModule(), new AwsModule(), FakeHttpClientModule(), PlayAppConfigurationModule())

}
