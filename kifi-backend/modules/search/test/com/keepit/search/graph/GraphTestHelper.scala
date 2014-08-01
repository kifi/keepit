package com.keepit.search.graph

import com.google.inject.Injector
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import com.keepit.search.graph.collection._
import com.keepit.search.graph.bookmark._
import com.keepit.search.graph.user._
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.inject._
import com.keepit.search.index.{ IndexDirectory, VolatileIndexDirectory, DefaultAnalyzer }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.test.SearchTestInjector
import play.api.test.Helpers._

trait GraphTestHelper extends SearchTestInjector {

  val source = KeepSource("test")
  val bigDataSize = 8000

  def initData()(implicit injector: Injector) = {
    val users = saveUsers(
      User(firstName = "Agrajag", lastName = ""),
      User(firstName = "Barmen", lastName = ""),
      User(firstName = "Colin", lastName = ""),
      User(firstName = "Dan", lastName = ""),
      User(firstName = "Eccentrica", lastName = ""),
      User(firstName = "Hactar", lastName = "")
    )
    val uris = saveURIs(
      NormalizedURI.withHash(title = Some("1"), normalizedUrl = "http://www.keepit.com/article1", state = SCRAPED),
      NormalizedURI.withHash(title = Some("2"), normalizedUrl = "http://www.keepit.com/article2", state = SCRAPED),
      NormalizedURI.withHash(title = Some("3"), normalizedUrl = "http://www.keepit.org/article3", state = SCRAPED),
      NormalizedURI.withHash(title = Some("4"), normalizedUrl = "http://www.findit.com/article4", state = SCRAPED),
      NormalizedURI.withHash(title = Some("5"), normalizedUrl = "http://www.findit.com/article5", state = SCRAPED),
      NormalizedURI.withHash(title = Some("6"), normalizedUrl = "http://www.findit.org/article6", state = SCRAPED)
    )
    (users, uris)
  }

  def superBigData()(implicit injector: Injector) = {
    val users = saveUsers(
      User(firstName = "rich", lastName = ""),
      User(firstName = "poor", lastName = "")
    )

    val uris = saveURIs(
      (1 to bigDataSize).map { i =>
        NormalizedURI.withHash(title = Some(s"${i}"), normalizedUrl = s"http://www.keepit.com/article${i}", state = SCRAPED)
      }: _*
    )
    (users, uris)
  }

  def shoeboxClient()(implicit injector: Injector) = inject[ShoeboxServiceClient]

  def saveUsers(users: User*)(implicit injector: Injector) = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveUsers(users: _*)
  }
  def saveURIs(uris: NormalizedURI*)(implicit injector: Injector) = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveURIs(uris: _*)
  }

  def setupArticleStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore) {
      case (store, (uri, idx)) =>
        store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs".format(idx)))
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
      titleLang = Some(Lang("en")),
      contentLang = Some(Lang("en")))
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], mixPrivate: Boolean = false, uniqueTitle: Option[String] = None)(implicit injector: Injector): List[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val edges = for ((uri, users) <- edgesByURI; user <- users) yield (uri, user, uniqueTitle)
    val (privateEdges, publicEdges) = edges.partition { case (uri, user, _) => (uri.id.get.id + user.id.get.id) % 2 == 0 }
    val bookmarks = fakeShoeboxServiceClient.saveBookmarksByEdges(privateEdges, isPrivate = mixPrivate && true, source = source) ++
      fakeShoeboxServiceClient.saveBookmarksByEdges(publicEdges, isPrivate = mixPrivate && false, source = source)
    bookmarks.toList
  }

  def saveBookmarksByEdges(edges: Seq[(NormalizedURI, User, Option[String])])(implicit injector: Injector): Seq[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksByEdges(edges, source = source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None)(implicit injector: Injector): Seq[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksByUser(edgesByUser, uniqueTitle = uniqueTitle, source = source)
  }

  def saveCollection(user: User, name: String)(implicit injector: Injector): Collection = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val Seq(collection) = fakeShoeboxServiceClient.saveCollections(Collection(userId = user.id.get, name = name))
    collection
  }

  def saveBookmarksToCollection(collection: Collection, bookmarks: Seq[Keep])(implicit injector: Injector): Collection = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksToCollection(collection.id.get, bookmarks: _*)
    fakeShoeboxServiceClient.getCollection(collection.id.get)
  }

  def mkURIGraphIndexer(uriGraphDir: IndexDirectory = new VolatileIndexDirectory(), bookmarkStoreDir: IndexDirectory = new VolatileIndexDirectory())(implicit injector: Injector): URIGraphIndexer = {
    val bookmarkStore = new BookmarkStore(bookmarkStoreDir, inject[AirbrakeNotifier])
    new StandaloneURIGraphIndexer(uriGraphDir, bookmarkStore, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  def mkCollectionIndexer(collectionDir: IndexDirectory = new VolatileIndexDirectory())(implicit injector: Injector): CollectionIndexer = {
    val collectionNameIndexer = new CollectionNameIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier])
    new StandaloneCollectionIndexer(collectionDir, collectionNameIndexer, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  def mkUserGraphsSearcherFactory()(implicit injector: Injector) = {
    val userGraphIndexer = new UserGraphIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val searchFriendIndexer = new SearchFriendIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val userGraphsSearcherFactory = new UserGraphsSearcherFactory(userGraphIndexer, searchFriendIndexer)
    (userGraphIndexer, searchFriendIndexer, userGraphsSearcherFactory)
  }

  def addConnections(connections: Map[Id[User], Set[Id[User]]])(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveConnections(connections)
  }

  def getBookmarksByUser(userId: Id[User])(implicit injector: Injector): Seq[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    await(fakeShoeboxServiceClient.getBookmarks(userId))
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User])(implicit injector: Injector): Option[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    await(fakeShoeboxServiceClient.getBookmarkByUriAndUser(uriId, userId))
  }
}
