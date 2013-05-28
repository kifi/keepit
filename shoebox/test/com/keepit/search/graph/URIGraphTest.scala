package com.keepit.search.graph

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.{Article, ArticleStore, Lang}
import com.keepit.search.index.{Searcher, WrappedIndexReader, WrappedSubReader}
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.ConditionalQuery
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.test._
import com.keepit.inject._
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.BooleanQuery
import scala.collection.JavaConversions._
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.index.{ArticleIndexer, DefaultAnalyzer}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.net.HttpClient
import com.keepit.common.net.FakeHttpClient
import play.api.libs.json.JsArray

class URIGraphTest extends Specification with DbRepos {

  private def setupDB = {
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

  private def setupArticleStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
      store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs".format(idx)))
      store
    }
  }

  private def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
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

  private def mkBookmarks(expectedUriToUserEdges: List[(NormalizedURI, List[User])], mixPrivate: Boolean = false): List[Bookmark] = {
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

  private def mkURIGraph(graphDir: RAMDirectory = new RAMDirectory, collectionDir: RAMDirectory = new RAMDirectory): URIGraphImpl = {
    val shoeboxClient = inject[ShoeboxServiceClient]
    val uriGraphIndexer = new URIGraphIndexer(graphDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), shoeboxClient)
    val collectionIndexer = new CollectionIndexer(collectionDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), shoeboxClient)
    new URIGraphImpl(uriGraphIndexer, collectionIndexer)
  }

  class Searchable(uriGraphSearcher: URIGraphSearcher) {
    def search(query: Query): Map[Long, Float] = {
      var result = Map.empty[Long,Float]
      uriGraphSearcher.openPersonalIndex(query) match {
        case Some((indexReader, idMapper)) =>
          val ir = new WrappedSubReader("", indexReader, idMapper)
          val searcher = new Searcher(new WrappedIndexReader(null, Array(ir)))
          val weight = searcher.createNormalizedWeight(query)
          val scorer = weight.scorer(ir.getContext, true, true, ir.getLiveDocs)
          if (scorer != null) {
            var doc = scorer.nextDoc()
            while (doc < NO_MORE_DOCS) {
              result += (idMapper.getId(doc) -> scorer.score())
              doc = scorer.nextDoc()
            }
          }
        case None =>
      }
      result
    }
  }
  implicit def toSearchable(uriGraphSearcher: URIGraphSearcher) = new Searchable(uriGraphSearcher: URIGraphSearcher)

  class TestDocIdSetIterator(docIds: Int*) extends DocIdSetIterator {
    val ids = docIds.toArray.sortWith(_ < _).distinct
    var i = -1
    def docID(): Int = {
      if (i < 0) -1
      else if (i < ids.length) ids(i)
      else NO_MORE_DOCS
    }
    def nextDoc(): Int = {
      if (i < ids.length) i += 1
      if (i < ids.length) ids(i) else NO_MORE_DOCS
    }
    def advance(target: Int): Int = {
      if (i < ids.length) i += 1
      while (i < ids.length) {
        if (ids(i) < target) i += 1
        else return ids(i)
      }
      NO_MORE_DOCS
    }
  }

  private def httpClientGetChangedUsers(seqNum: Long) = {
                val changed = db.readOnly { implicit s =>
                  bookmarkRepo.getUsersChanged(SequenceNumber(seqNum))
                } map {
                  case (userId, seqNum) =>
                    Json.obj("id" -> userId.id, "seqNum" -> seqNum.value)
                }
                JsArray(changed)
              }

  "URIGraph" should {
    "maintain a sequence number on bookmarks " in {
     running(new DevApplication().withShoeboxServiceModule)
            {
        val (users, uris) = setupDB
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges)

        val graphDir = new RAMDirectory
        val graph = mkURIGraph(graphDir)
        graph.update() === users.size
        graph.uriGraphIndexer.sequenceNumber.value === bookmarks.size
        val graph2 = mkURIGraph(graphDir)
        graph2.uriGraphIndexer.sequenceNumber.value === bookmarks.size
      }
    }
    "generate UriToUsrEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges)
        val graph = mkURIGraph()
        graph.update() === users.size

        val searcher = graph.getURIGraphSearcher()

        expectedUriToUserEdges.map{ case (uri, users) =>
          val expected = users.map(_.id.get).toSet
          val answer = searcher.getUriToUserEdgeSet(uri.id.get).destIdSet
          answer === expected
        }

        graph.uriGraphIndexer.numDocs === users.size
      }
    }

    "generate UserToUriEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule){
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges, mixPrivate = true)
        val graph = mkURIGraph()
        graph.update() === users.size

        val searcher = graph.getURIGraphSearcher()

        val expectedUserIdToUriIdEdges = bookmarks.groupBy(_.userId).map{ case (userId, bookmarks) => (userId, bookmarks.map(_.uriId)) }
        expectedUserIdToUriIdEdges.map{ case (userId, uriIds) =>
          val expected = uriIds.toSet
          val answer = searcher.getUserToUriEdgeSet(userId, publicOnly = false).destIdSet
          answer === expected

          val expectedPublicOnly = uriIds.filterNot{ uriId => (uriId.id + userId.id) % 2 == 0 }.toSet
          val answerPublicOnly = searcher.getUserToUriEdgeSet(userId, publicOnly = true).destIdSet
          answerPublicOnly === expectedPublicOnly
        }

        graph.uriGraphIndexer.numDocs === users.size
      }
    }

    "intersect UserToUserEdgeSet and UriToUserEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges)
        val graph = mkURIGraph()
        graph.update() === users.size

        val searcher = graph.getURIGraphSearcher()

        users.sliding(3).foreach{ friends =>
          val friendIds = friends.map(_.id.get).toSet
          val userToUserEdgeSet = UserToUserEdgeSet(Id[User](1000), friendIds)

          expectedUriToUserEdges.map{ case (uri, users) =>
            val expected = (users.map(_.id.get).toSet intersect friendIds)
            val answer = searcher.intersect(userToUserEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet
            //println("friends:"+ friendIds)
            //println("users:" + users.map(_.id.get))
            //println("expected:" + expected)
            //println("answer:" + answer)
            //println("---")
            answer === expected
          }
        }

        graph.uriGraphIndexer.numDocs === users.size
      }
    }

    "intersect empty sets" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getURIGraphSearcher()

        searcher.getUserToUriEdgeSet(Id[User](10000)).destIdSet.isEmpty === true

        searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000)).destIdSet.isEmpty === true

        val emptyUserToUserEdgeSet = UserToUserEdgeSet(Id[User](10000), Set.empty[Id[User]])

        emptyUserToUserEdgeSet.destIdSet.isEmpty === true

        searcher.intersect(emptyUserToUserEdgeSet, searcher.getUriToUserEdgeSet(uris.head.id.get)).destIdSet.isEmpty === true

        searcher.intersect(emptyUserToUserEdgeSet, searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000))).destIdSet.isEmpty === true

        val userToUserEdgeSet = UserToUserEdgeSet(Id[User](10000), users.map(_.id.get).toSet)
        searcher.intersect(userToUserEdgeSet, searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000))).destIdSet.isEmpty === true
      }
    }

    "determine whether intersection is empty" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getURIGraphSearcher()
        searcher.intersectAny(new TestDocIdSetIterator(1, 2, 3), new TestDocIdSetIterator(2, 4, 6)) === true
        searcher.intersectAny(new TestDocIdSetIterator(       ), new TestDocIdSetIterator(       )) === false
        searcher.intersectAny(new TestDocIdSetIterator(       ), new TestDocIdSetIterator(2, 4, 6)) === false
        searcher.intersectAny(new TestDocIdSetIterator(1, 2, 3), new TestDocIdSetIterator(       )) === false
        searcher.intersectAny(new TestDocIdSetIterator(1, 3, 5), new TestDocIdSetIterator(2, 4, 6)) === false
      }
    }

    "search personal bookmark titles" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        db.readWrite { implicit s =>
          uris.foreach{ uri =>
            val uriId =  uri.id.get
            val url1 = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = ("personaltitle bmt"+uriId), url = url1,  uriId = uriId, userId = users((uriId.id % 2L).toInt).id.get, source = BookmarkSource("test")))
          }
        }
        val graph = mkURIGraph()
        graph.update()

        val personaltitle = new TermQuery(new Term(URIGraphFields.titleField, "personaltitle"))
        val bmt1 = new TermQuery(new Term(URIGraphFields.titleField, "bmt1"))
        val bmt2 = new TermQuery(new Term(URIGraphFields.titleField, "bmt2"))

        val searcher0 = graph.getURIGraphSearcher(users(0).id)
        val searcher1 = graph.getURIGraphSearcher(users(1).id)

        searcher0.search(personaltitle).keySet === Set(2L, 4L, 6L)
        searcher1.search(personaltitle).keySet === Set(1L, 3L, 5L)

        searcher0.search(bmt1).keySet === Set.empty[Long]
        searcher1.search(bmt1).keySet === Set(1L)

        searcher0.search(bmt2).keySet === Set(2L)
        searcher1.search(bmt2).keySet === Set.empty[Long]
      }
    }

    "search personal bookmark domains" in {
       running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        db.readWrite { implicit s =>
          uris.foreach{ uri =>
            val uriId =  uri.id.get
            val url1 = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = ("personaltitle bmt"+uriId), url = url1,  uriId = uriId, userId = users(0).id.get, source = BookmarkSource("test")))
          }
        }
        val graph = mkURIGraph()
        graph.update() === 1

        val searcher = graph.getURIGraphSearcher(users(0).id)

        def mkSiteQuery(site: String) = {
          new ConditionalQuery(new TermQuery(new Term("title", "personaltitle")), SiteQuery(site))
        }

        var site = mkSiteQuery("com")
        searcher.search(site).keySet === Set(1L, 2L, 4L, 5L)


        site = mkSiteQuery("keepit.com")
        searcher.search(site).keySet === Set(1L, 2L)

        site = mkSiteQuery("org")
        searcher.search(site).keySet === Set(3L, 6L)

        site = mkSiteQuery("findit.org")
        searcher.search(site).keySet === Set(6L)

        site = mkSiteQuery(".org")
        searcher.search(site).keySet === Set(3L, 6L)

        site = mkSiteQuery(".findit.org")
        searcher.search(site).keySet === Set(6L)
      }
    }

    "dump Lucene Document" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val store = new FakeArticleStore()

        val (user, uris, bookmarks) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Agrajag", lastName = ""))
          val uris = Array(
            uriRepo.save(NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article1", state=SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article2", state=SCRAPED))
          )

          val url1 = urlRepo.save(URLFactory(url = uris(0).url, normalizedUriId = uris(0).id.get))
          val url2 = urlRepo.save(URLFactory(url = uris(1).url, normalizedUriId = uris(1).id.get))

          val bookmarks = uris.map{ uri =>
            val url = urlRepo.save(URLFactory(url = uris(0).url, normalizedUriId = uris(0).id.get))
            bookmarkRepo.save(BookmarkFactory(title = "line1 titles", url = url,  uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")))
          }
          (user, uris, bookmarks)
        }
        uris.foreach{ uri => store += (uri.id.get -> mkArticle(uri.id.get, "title", "content")) }

        val graph = mkURIGraph()
        val doc = graph.uriGraphIndexer.buildIndexable(user.id.get, SequenceNumber.ZERO).buildDocument
        doc.getFields.forall{ f => graph.uriGraphIndexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
