package com.keepit.search.graph

import com.keepit.common.akka.MonitoredAwait
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
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.index.{IndexDirectory, VolatileIndexDirectoryImpl, DefaultAnalyzer}
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.Play.current
import play.api.test.Helpers._

trait GraphTestHelper extends ApplicationInjector {

  val source = BookmarkSource("test")
  val bigDataSize = 8000

  def initData = {
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

  def superBigData = {
    val users = saveUsers(
      User(firstName = "rich", lastName = ""),
      User(firstName = "poor", lastName = "")
    )

    val uris = saveURIs(
      (1 to bigDataSize).map{ i =>
        NormalizedURI.withHash(title = Some(s"${i}"), normalizedUrl = s"http://www.keepit.com/article${i}", state = SCRAPED)
      } :_*
    )
    (users, uris)
  }

  def shoeboxClient = inject[ShoeboxServiceClient]

  def saveUsers(users: User*) = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveUsers(users:_*)
  }
  def saveURIs(uris: NormalizedURI*) = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveURIs(uris:_*)
  }

  def setupArticleStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
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

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], mixPrivate: Boolean = false, uniqueTitle: Option[String] = None): List[Bookmark] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val edges = for ((uri, users) <- edgesByURI; user <- users) yield (uri, user, uniqueTitle)
    val (privateEdges, publicEdges) = edges.partition {case (uri, user, _) => (uri.id.get.id + user.id.get.id) % 2 == 0}
    val bookmarks = fakeShoeboxServiceClient.saveBookmarksByEdges(privateEdges, isPrivate = mixPrivate && true, source = source) ++
    fakeShoeboxServiceClient.saveBookmarksByEdges(publicEdges, isPrivate = mixPrivate && false, source = source)
    bookmarks.toList
  }

  def saveBookmarksByEdges(edges: Seq[(NormalizedURI, User, Option[String])]): Seq[Bookmark] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksByEdges(edges, source = source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None): Seq[Bookmark] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksByUser(edgesByUser, uniqueTitle = uniqueTitle, source = source)
  }

  def saveCollection(user: User, name: String): Collection = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val Seq(collection) = fakeShoeboxServiceClient.saveCollections(Collection(userId = user.id.get, name = name))
    collection
  }

  def saveBookmarksToCollection(collection: Collection, bookmarks: Seq[Bookmark]): Collection = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksToCollection(collection.id.get, bookmarks:_*)
    fakeShoeboxServiceClient.getCollection(collection.id.get)
  }

  def mkURIGraphIndexer(uriGraphDir: IndexDirectory = new VolatileIndexDirectoryImpl(), bookmarkStoreDir: IndexDirectory = new VolatileIndexDirectoryImpl()): URIGraphIndexer = {
    val bookmarkStore = new BookmarkStore(bookmarkStoreDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier])
    new StandaloneURIGraphIndexer(uriGraphDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), bookmarkStore, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  def mkCollectionIndexer(collectionDir: IndexDirectory = new VolatileIndexDirectoryImpl()): CollectionIndexer = {
    val collectionNameIndexer = new CollectionNameIndexer(new VolatileIndexDirectoryImpl, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier])
    new StandaloneCollectionIndexer(collectionDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), collectionNameIndexer, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  def mkUserGraphsCommander() = {
    val userGraphIndexer = new UserGraphIndexer(new VolatileIndexDirectoryImpl, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val searchFriendIndexer = new SearchFriendIndexer(new VolatileIndexDirectoryImpl, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val commander = new UserGraphsCommander(userGraphIndexer, searchFriendIndexer)
    (userGraphIndexer, searchFriendIndexer, commander)
  }


  def addConnections(connections: Map[Id[User], Set[Id[User]]]) {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveConnections(connections)
  }

  def getBookmarksByUser(userId: Id[User]): Seq[Bookmark] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    await(fakeShoeboxServiceClient.getBookmarks(userId))
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Option[Bookmark] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    await(fakeShoeboxServiceClient.getBookmarkByUriAndUser(uriId, userId))
  }
}
