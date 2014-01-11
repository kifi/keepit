package com.keepit.search.graph.bookmark

import com.keepit.search.index.{WrappedIndexReader, WrappedSubReader}
import com.keepit.search.Searcher
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.ConditionalQuery
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import com.keepit.search.graph.bookmark._
import com.google.inject.Singleton
import com.keepit.search.graph.GraphTestHelper
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait


class URIGraphSearcherTest extends Specification with GraphTestHelper {

  class Searchable(uriGraphSearcher: URIGraphSearcherWithUser) {
    def search(query: Query): Map[Long, Float] = {
      var result = Map.empty[Long,Float]
      val (indexReader, idMapper) = uriGraphSearcher.openPersonalIndex(query)
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
      result
    }
  }
  implicit def toSearchable(uriGraphSearcher: URIGraphSearcherWithUser) = new Searchable(uriGraphSearcher)

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

  "URIGraph" should {
    "generate UriToUsrEdgeSet" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        saveBookmarksByURI(expectedUriToUserEdges)
        val indexer = mkURIGraphIndexer()
        indexer.update() === users.size

        val searcher = URIGraphSearcher(indexer)

        expectedUriToUserEdges.map{ case (uri, users) =>
          val expected = users.map(_.id.get).toSet
          val answer = searcher.getUriToUserEdgeSet(uri.id.get).destIdSet
          answer === expected
        }

        indexer.numDocs === users.size
      }
    }

    "generate UserToUriEdgeSet" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule){
        val (users, uris) = initData

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = saveBookmarksByURI(expectedUriToUserEdges, mixPrivate = true)
        val indexer = mkURIGraphIndexer()
        indexer.update() === users.size

        val searcher = URIGraphSearcher(indexer)

        val expectedUserIdToUriIdEdges = bookmarks.groupBy(_.userId).map{ case (userId, bookmarks) => (userId, bookmarks.map(_.uriId)) }
        expectedUserIdToUriIdEdges.map{ case (userId, uriIds) =>
          val expected = uriIds.toSet
          val answer = searcher.getUserToUriEdgeSet(userId, publicOnly = false).destIdSet
          answer === expected

          val expectedPublicOnly = uriIds.filterNot{ uriId => (uriId.id + userId.id) % 2 == 0 }.toSet
          val answerPublicOnly = searcher.getUserToUriEdgeSet(userId, publicOnly = true).destIdSet
          answerPublicOnly === expectedPublicOnly
        }

        indexer.numDocs === users.size
      }
    }

    "intersect UserToUserEdgeSet and UriToUserEdgeSet" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = saveBookmarksByURI(expectedUriToUserEdges)
        val indexer = mkURIGraphIndexer()
        indexer.update() === users.size

        val searcher = URIGraphSearcher(indexer)

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

        indexer.numDocs === users.size
      }
    }

    "intersect empty sets" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData
        val indexer = mkURIGraphIndexer()

        val searcher = URIGraphSearcher(indexer)

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
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val indexer = mkURIGraphIndexer()
        indexer.update()

        val searcher = URIGraphSearcher(indexer)
        searcher.intersectAny(new TestDocIdSetIterator(1, 2, 3), new TestDocIdSetIterator(2, 4, 6)) === true
        searcher.intersectAny(new TestDocIdSetIterator(       ), new TestDocIdSetIterator(       )) === false
        searcher.intersectAny(new TestDocIdSetIterator(       ), new TestDocIdSetIterator(2, 4, 6)) === false
        searcher.intersectAny(new TestDocIdSetIterator(1, 2, 3), new TestDocIdSetIterator(       )) === false
        searcher.intersectAny(new TestDocIdSetIterator(1, 3, 5), new TestDocIdSetIterator(2, 4, 6)) === false
      }
    }

    "search personal bookmark titles" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData
        val store = setupArticleStore(uris)
        val edges = uris.map { uri => (uri, users((uri.id.get.id % 2L).toInt), Some("personaltitle bmt" + uri.id.get.id))}
        saveBookmarksByEdges(edges)

        val indexer = mkURIGraphIndexer()
        indexer.update()

        val personaltitle = new TermQuery(new Term(URIGraphFields.titleField, "personaltitle"))
        val bmt1 = new TermQuery(new Term(URIGraphFields.titleField, "bmt1"))
        val bmt2 = new TermQuery(new Term(URIGraphFields.titleField, "bmt2"))

        addConnections(Map(users(0).id.get -> Set(), users(1).id.get -> Set()))

        val searcher0 = URIGraphSearcher(users(0).id.get, indexer, inject[ShoeboxServiceClient], inject[MonitoredAwait])
        val searcher1 = URIGraphSearcher(users(1).id.get, indexer, inject[ShoeboxServiceClient], inject[MonitoredAwait])

        searcher0.search(personaltitle).keySet === Set(2L, 4L, 6L)
        searcher1.search(personaltitle).keySet === Set(1L, 3L, 5L)

        searcher0.search(bmt1).keySet === Set.empty[Long]
        searcher1.search(bmt1).keySet === Set(1L)

        searcher0.search(bmt2).keySet === Set(2L)
        searcher1.search(bmt2).keySet === Set.empty[Long]
      }
    }

    "search personal bookmark domains" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData
        val store = setupArticleStore(uris)
        val edges = uris.map { uri => (uri, users(0), Some("personaltitle bmt" + uri.id.get.id))}
        saveBookmarksByEdges(edges)

        val indexer = mkURIGraphIndexer()
        indexer.update() === 1

        addConnections(Map(users(0).id.get -> Set()))

        val searcher = URIGraphSearcher(users(0).id.get, indexer, inject[ShoeboxServiceClient], inject[MonitoredAwait])

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

    "search personal bookmark url keyowrd" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData
        val store = setupArticleStore(uris)
        val edges = uris.map { uri => (uri, users(0), Some("personaltitle bmt" + uri.id.get.id))}
        saveBookmarksByEdges(edges)

        val indexer = mkURIGraphIndexer()
        indexer.update() === 1

        addConnections(Map(users(0).id.get -> Set()))

        val searcher = URIGraphSearcher(users(0).id.get, indexer, inject[ShoeboxServiceClient], inject[MonitoredAwait])

        def mkQuery(word: String) = new TermQuery(new Term("title", word))

        var qry = mkQuery("com")
        searcher.search(qry).keySet === Set(1L, 2L, 4L, 5L)


        qry = mkQuery("keepit")
        searcher.search(qry).keySet === Set(1L, 2L, 3L)

        qry = mkQuery("org")
        searcher.search(qry).keySet === Set(3L, 6L)

        qry = mkQuery("findit")
        searcher.search(qry).keySet === Set(4L, 5L, 6L)

        qry = mkQuery("article1")
        searcher.search(qry).keySet === Set(1L)

        qry = mkQuery("article2")
        searcher.search(qry).keySet === Set(2L)
      }
    }

    "retrieve bookmark records from bookmark store" in {
       running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData
        val store = setupArticleStore(uris)
        val edges = uris.take(3).map { uri => (uri, users(0), Some("personaltitle bmt" + uri.id.get.id))}
        saveBookmarksByEdges(edges)

        val indexer = mkURIGraphIndexer()
        indexer.update() === 1

        addConnections(Map(users(0).id.get -> Set()))

        val searcher = URIGraphSearcher(users(0).id.get, indexer, inject[ShoeboxServiceClient], inject[MonitoredAwait])

        uris.take(3).foreach{ uri =>
          val uriId =  uri.id.get
          val recOpt = searcher.getBookmarkRecord(uriId)
          recOpt must beSome[BookmarkRecord]
          recOpt.map{ rec =>
            rec.title === ("personaltitle bmt"+uriId)
            rec.url === uri.url
          }
        }
      }
    }
  }
}
