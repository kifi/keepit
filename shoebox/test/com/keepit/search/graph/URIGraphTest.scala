package com.keepit.search.graph

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.{Article, ArticleStore, Lang}
import com.keepit.search.index.{ArrayIdMapper, Searcher, WrappedIndexReader}
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.ConditionalQuery
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.test._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
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

@RunWith(classOf[JUnitRunner])
class URIGraphTest extends SpecificationWithJUnit with DbRepos {

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
        content = content,
        scrapedAt = currentDateTime,
        httpContentType = Some("text/html"),
        httpOriginalContentCharset = Option("UTF-8"),
        state = SCRAPED,
        message = None,
        titleLang = Some(Lang("en")),
        contentLang = Some(Lang("en")))
  }

  class Searchable(uriGraphSearcher: URIGraphSearcher) {
    def search(user: Id[User], query: Query): Map[Long, Float] = {
      uriGraphSearcher.openPersonalIndex(user, query) match {
        case Some((indexReader, idMapper)) =>
          val ir = WrappedIndexReader(indexReader, idMapper)
          val searcher = new Searcher(ir)
          var result = Map.empty[Long,Float]
          searcher.doSearch(query){ (scorer, idMapper) =>
            var doc = scorer.nextDoc()
            while (doc < NO_MORE_DOCS) {
              result += (idMapper.getId(doc) -> scorer.score())
              doc = scorer.nextDoc()
            }
          }
          result
        case None => Map.empty[Long, Float]
      }
    }
  }
  implicit def toSearchable(uriGraphSearcher: URIGraphSearcher) = new Searchable(uriGraphSearcher: URIGraphSearcher)

  class TestDocIdSetIterator(ids: Array[Int]) extends DocIdSetIterator {
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

  "URIGraph" should {
    "generate UriToUsrEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = db.readWrite { implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map { user =>
              val url1 = urlRepo.get(uri.url).getOrElse( urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
              bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1, uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")))
            }
          }
        }

        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir).asInstanceOf[URIGraphImpl]
        graph.load() === users.size

        val searcher = graph.getURIGraphSearcher()

        expectedUriToUserEdges.map{ case (uri, users) =>
          val expected = users.map(_.id.get).toSet
          val answer = searcher.getUriToUserEdgeSet(uri.id.get).destIdSet
          answer === expected
        }

        graph.numDocs === users.size
      }
    }

    "generate UserToUriEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = db.readWrite { implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
              bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1, uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")))
            }
          }
        }

        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir).asInstanceOf[URIGraphImpl]
        graph.load() === users.size

        val searcher = graph.getURIGraphSearcher()

        val expectedUserIdToUriIdEdges = bookmarks.groupBy(_.userId).map{ case (userId, bookmarks) => (userId, bookmarks.map(_.uriId)) }
        expectedUserIdToUriIdEdges.map{ case (userId, uriIds) =>
          val expected = uriIds.toSet
          val answer = searcher.getUserToUriEdgeSet(userId).destIdSet
          answer === expected
        }

        graph.numDocs === users.size
      }
    }

    "intersect UserToUserEdgeSet and UriToUserEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = db.readWrite { implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
              bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1, uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")))
            }
          }
        }

        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir).asInstanceOf[URIGraphImpl]
        graph.load() === users.size

        val searcher = graph.getURIGraphSearcher()

        users.sliding(3).foreach{ friends =>
          val friendIds = friends.map(_.id.get).toSet
          val userToUserEdgeSet = new UserToUserEdgeSet(Id[User](1000), friendIds)

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

        graph.numDocs === users.size
      }
    }

    "intersect empty sets" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB

        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir)
        val searcher = graph.getURIGraphSearcher()

        searcher.getUserToUriEdgeSet(Id[User](10000)).destIdSet.isEmpty === true

        searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000)).destIdSet.isEmpty === true

        val emptyUserToUserEdgeSet = new UserToUserEdgeSet(Id[User](10000), Set())

        emptyUserToUserEdgeSet.destIdSet.isEmpty === true

        searcher.intersect(emptyUserToUserEdgeSet, searcher.getUriToUserEdgeSet(uris.head.id.get)).destIdSet.isEmpty === true

        searcher.intersect(emptyUserToUserEdgeSet, searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000))).destIdSet.isEmpty === true

        val userToUserEdgeSet = new UserToUserEdgeSet(Id[User](10000), users.map(_.id.get).toSet)
        searcher.intersect(userToUserEdgeSet, searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000))).destIdSet.isEmpty === true
      }
    }

    "determine whether intersection is empty" in {
      new URIGraphSearcher(null).intersectAny(
        new TestDocIdSetIterator(Array(1, 2, 3)),
        new TestDocIdSetIterator(Array(2, 4, 6))) === true
      new URIGraphSearcher(null).intersectAny(
        new TestDocIdSetIterator(Array()),
        new TestDocIdSetIterator(Array(2, 4, 6))) === false
      new URIGraphSearcher(null).intersectAny(
        new TestDocIdSetIterator(Array(1, 2, 3)),
        new TestDocIdSetIterator(Array())) === false
      new URIGraphSearcher(null).intersectAny(
        new TestDocIdSetIterator(Array(1, 3, 5)),
        new TestDocIdSetIterator(Array(2, 4, 6))) === false
    }

    "search personal bookmark titles" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        db.readWrite { implicit s =>
          uris.foreach{ uri =>
            val uriId =  uri.id.get
            val url1 = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = ("personaltitle bmt"+uriId), url = url1,  uriId = uriId, userId = users((uriId.id % 2L).toInt).id.get, source = BookmarkSource("test")))
          }
        }

        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir).asInstanceOf[URIGraphImpl]
        graph.load() === users.size

        val searcher = graph.getURIGraphSearcher()
        val personaltitle = new TermQuery(URIGraph.titleTerm.createTerm("personaltitle"))
        val bmt1 = new TermQuery(URIGraph.titleTerm.createTerm("bmt1"))
        val bmt2 = new TermQuery(URIGraph.titleTerm.createTerm("bmt2"))

        searcher.search(users(0).id.get, personaltitle).keySet === Set(2L, 4L, 6L)
        searcher.search(users(1).id.get, personaltitle).keySet === Set(1L, 3L, 5L)

        searcher.search(users(0).id.get, bmt1).keySet === Set.empty[Long]
        searcher.search(users(1).id.get, bmt1).keySet === Set(1L)

        searcher.search(users(0).id.get, bmt2).keySet === Set(2L)
        searcher.search(users(1).id.get, bmt2).keySet === Set.empty[Long]
      }
    }

    "search personal bookmark domains" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        db.readWrite { implicit s =>
          uris.foreach{ uri =>
            val uriId =  uri.id.get
            val url1 = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = ("personaltitle bmt"+uriId), url = url1,  uriId = uriId, userId = users(0).id.get, source = BookmarkSource("test")))
          }
        }

        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir).asInstanceOf[URIGraphImpl]
        graph.load() === users.size

        val searcher = graph.getURIGraphSearcher()

        def mkSiteQuery(site: String) = {
          new ConditionalQuery(new TermQuery(new Term("title", "personaltitle")), SiteQuery(site))
        }

        var site = mkSiteQuery("com")
        searcher.search(users(0).id.get, site).keySet === Set(1L, 2L, 4L, 5L)


        site = mkSiteQuery("keepit.com")
        searcher.search(users(0).id.get, site).keySet === Set(1L, 2L)

        site = mkSiteQuery("org")
        searcher.search(users(0).id.get, site).keySet === Set(3L, 6L)

        site = mkSiteQuery("findit.org")
        searcher.search(users(0).id.get, site).keySet === Set(6L)

        site = mkSiteQuery(".org")
        searcher.search(users(0).id.get, site).keySet === Set(3L, 6L)

        site = mkSiteQuery(".findit.org")
        searcher.search(users(0).id.get, site).keySet === Set(6L)
      }
    }

    "dump Lucene Document" in {
      running(new EmptyApplication()) {
        val ramDir = new RAMDirectory
        val store = new FakeArticleStore()

        val (user, uris, bookmarks) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Agrajag", lastName = ""))
          val uris = Array(
            uriRepo.save(NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article1", state=SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article2", state=SCRAPED))
          )

          val url1 = urlRepo.save(URLFactory(url = uris(0).url, normalizedUriId = uris(0).id.get))
          val url2 = urlRepo.save(URLFactory(url = uris(1).url, normalizedUriId = uris(1).id.get))

          val bookmarks = Array(
            bookmarkRepo.save(BookmarkFactory(title = "line1 titles", url = url1,  uriId = uris(0).id.get, userId = user.id.get, source = BookmarkSource("test"))),
            bookmarkRepo.save(BookmarkFactory(title = "line2 titles", url = url2,  uriId = uris(1).id.get, userId = user.id.get, source = BookmarkSource("test")))
          )
          (user, uris, bookmarks)
        }
        uris.foreach{ uri => store += (uri.id.get -> mkArticle(uri.id.get, "title", "content")) }

        val indexer = URIGraph(ramDir).asInstanceOf[URIGraphImpl]
        val doc = indexer.buildIndexable(user.id.get).buildDocument
        doc.getFields.forall{ f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
