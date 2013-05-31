package com.keepit.search.graph

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.test._
import com.keepit.inject._
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

trait GraphTestHelper extends DbRepos {
  def setupDB = {
    db.readWrite { implicit s =>
      val users = List(
            userRepo.save(User(firstName = "Agrajag", lastName = "")),
            userRepo.save(User(firstName = "Barmen", lastName = "")),
            userRepo.save(User(firstName = "Colin", lastName = "")),
            userRepo.save(User(firstName = "Dan", lastName = "")),
            userRepo.save(User(firstName = "Eccentrica", lastName = "")),
            userRepo.save(User(firstName = "Hactar", lastName = "")))
      val uris = List(
            uriRepo.save(NormalizedURIFactory(title = "a1", url = "http://www.keepit.com/article1", state = SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "a2", url = "http://www.keepit.com/article2", state = SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "a3", url = "http://www.keepit.org/article3", state = SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "a4", url = "http://www.findit.com/article4", state = SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "a5", url = "http://www.findit.com/article5", state = SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "a6", url = "http://www.findit.org/article6", state = SCRAPED)))
      (users, uris)
    }
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

  def mkBookmarks(expectedUriToUserEdges: List[(NormalizedURI, List[User])], mixPrivate: Boolean = false): List[Bookmark] = {
    db.readWrite { implicit s =>
      expectedUriToUserEdges.flatMap{ case (uri, users) =>
        users.map { user =>
          val url1 = urlRepo.get(uri.url).getOrElse( urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
          bookmarkRepo.save(BookmarkFactory(
            uri = uri,
            userId = user.id.get,
            title = uri.title,
            url = url1,
            source = BookmarkSource("test"),
            isPrivate = mixPrivate && ((uri.id.get.id + user.id.get.id) % 2 == 0),
            kifiInstallation = None))
        }
      }
    }
  }

  def mkCollection(user: User, name: String): Collection = {
    db.readWrite { implicit s => collectionRepo.save(Collection(userId = user.id.get, name = name)) }
  }

  def addBookmarks(collection: Collection, bookmarks: Seq[Bookmark]): Collection = {
    db.readWrite { implicit s =>
      bookmarks.map { bookmark =>
        keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark.id.get, collectionId = collection.id.get))
      }
      collectionRepo.get(collection.id.get)
    }
  }

  def mkURIGraphIndexer(uriGraphDir: RAMDirectory = new RAMDirectory): URIGraphIndexer = {
    new URIGraphIndexer(uriGraphDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[ShoeboxServiceClient])
  }

  def mkCollectionIndexer(collectionDir: RAMDirectory = new RAMDirectory): CollectionIndexer = {
    new CollectionIndexer(collectionDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[ShoeboxServiceClient])
  }

  def mkURIGraph(graphDir: RAMDirectory = new RAMDirectory, collectionDir: RAMDirectory = new RAMDirectory): URIGraphImpl = {
    new URIGraphImpl(mkURIGraphIndexer(graphDir), mkCollectionIndexer(collectionDir), inject[ShoeboxServiceClient], inject[MonitoredAwait])
  }

  def setConnections(connections: Map[Id[User], Set[Id[User]]]) {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].setConnections(connections)
  }
}
