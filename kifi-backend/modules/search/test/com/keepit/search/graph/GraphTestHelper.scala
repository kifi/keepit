package com.keepit.search.graph

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.inject._
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.Play.current
import play.api.test.Helpers._

trait GraphTestHelper {

  val source = BookmarkSource("test")

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
      NormalizedURIFactory(title = "a1", url = "http://www.keepit.com/article1", state = SCRAPED),
      NormalizedURIFactory(title = "a2", url = "http://www.keepit.com/article2", state = SCRAPED),
      NormalizedURIFactory(title = "a3", url = "http://www.keepit.org/article3", state = SCRAPED),
      NormalizedURIFactory(title = "a4", url = "http://www.findit.com/article4", state = SCRAPED),
      NormalizedURIFactory(title = "a5", url = "http://www.findit.com/article5", state = SCRAPED),
      NormalizedURIFactory(title = "a6", url = "http://www.findit.org/article6", state = SCRAPED)
    )
    (users, uris)
  }

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

  def saveComment(comment: Comment, optionalRecipients: Id[User]*): Comment = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveComment(comment, optionalRecipients:_*)
  }

  def mkURIGraphIndexer(uriGraphDir: RAMDirectory = new RAMDirectory): URIGraphIndexer = {
    val bookmarkStore = new BookmarkStore(new RAMDirectory, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[HealthcheckPlugin], inject[ShoeboxServiceClient])
    new URIGraphIndexer(uriGraphDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), bookmarkStore, inject[HealthcheckPlugin], inject[ShoeboxServiceClient])
  }

  def mkCollectionIndexer(collectionDir: RAMDirectory = new RAMDirectory): CollectionIndexer = {
    new CollectionIndexer(collectionDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[HealthcheckPlugin], inject[ShoeboxServiceClient])
  }

  def mkURIGraph(graphDir: RAMDirectory = new RAMDirectory, collectionDir: RAMDirectory = new RAMDirectory): URIGraphImpl = {
    new URIGraphImpl(mkURIGraphIndexer(graphDir), mkCollectionIndexer(collectionDir), inject[ShoeboxServiceClient], inject[MonitoredAwait])
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
