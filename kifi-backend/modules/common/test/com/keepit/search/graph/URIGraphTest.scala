package com.keepit.search.graph

import com.keepit.search.index.{Searcher, WrappedIndexReader, WrappedSubReader}
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.ConditionalQuery
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.test._
import org.specs2.mutable._
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.BooleanQuery

class URIGraphTest extends Specification with GraphTestHelper {

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

  "URIGraph" should {
    "generate UriToUsrEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

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

    "generate UserToCollectionEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val usersWithCollection = users.take(2)
        val expectedUriToUserEdges = uris.map{ (_, usersWithCollection) }
        mkBookmarks(expectedUriToUserEdges)

        val collections = usersWithCollection.foldLeft(Map.empty[Id[User], Collection]){ (m, user) =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmarks = db.readOnly { implicit s =>
            bookmarkRepo.getByUser(user.id.get)
          }
          m + (user.id.get -> addBookmarks(coll, bookmarks))
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        val positiveUsers = usersWithCollection.map(_.id.get).toSet
        users.forall{ user =>
          val answer = searcher.getUserToCollectionEdgeSet(user.id.get)
          val expected = collections.get(user.id.get).map(_.id.get).toSet
          answer.destIdSet === expected
          true
        } === true
      }
    }

    "generate UriToCollectionEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter( _.id.get.id == uri.id.get.id)) }
        mkBookmarks(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]){ (m, user) =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmarks = db.readOnly { implicit s =>
            bookmarkRepo.getByUser(user.id.get)
          }
          m + (user -> addBookmarks(coll, bookmarks))
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        expectedUriToUsers.forall{ case (uri, users) =>
          val answer = searcher.getUriToCollectionEdgeSet(uri.id.get)
          val expected = users.flatMap{ user => collections.get(user) }.map(_.id.get).toSet
          answer.destIdSet === expected
          true
        } === true
      }
    }

    "generate CollectionToUriEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter{ _.id.get.id <= uri.id.get.id }) }
        mkBookmarks(expectedUriToUsers)

        val collections = users.map{ user =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmarks = db.readOnly { implicit s =>
            bookmarkRepo.getByUser(user.id.get)
          }
          (addBookmarks(coll, bookmarks), bookmarks)
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        collections.forall{ case (coll, bookmarks) =>
          val answer = searcher.getCollectionToUriEdgeSet(coll.id.get)
          answer.destIdSet === bookmarks.map(_.uriId).toSet
          true
        } === true
      }
    }

    "intersect UserToCollectionEdgeSet and UriToCollectionEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter( _.id.get.id == uri.id.get.id)) }
        mkBookmarks(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]){ (m, user) =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmarks = db.readOnly { implicit s =>
            bookmarkRepo.getByUser(user.id.get)
          }
          m + (user -> addBookmarks(coll, bookmarks))
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        expectedUriToUsers.forall{ case (uri, users) =>
          val uriToColl = searcher.getUriToCollectionEdgeSet(uri.id.get)
          val expectedFromUri = users.flatMap{ user => collections.get(user) }.map(_.id.get).toSet
          users.forall{ user =>
            val userToColl = searcher.getUserToCollectionEdgeSet(user.id.get)
            val expectedFromUser = collections.get(user).map(_.id.get).toSet

            searcher.intersect(userToColl, uriToColl).destIdSet === (expectedFromUri intersect expectedFromUser)
            true
          }
        } === true
      }
    }

    "intersect UserToUserEdgeSet and UriToUserEdgeSet" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

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

        setConnections(Map(users(0).id.get -> Set(), users(1).id.get -> Set()))

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

        setConnections(Map(users(0).id.get -> Set()))

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

    "retrieve bookmark records from bookmark store" in {
       running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        db.readWrite { implicit s =>
          uris.take(3).foreach{ uri =>
            val uriId =  uri.id.get
            val url1 = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = ("personaltitle bmt"+uriId), url = url1,  uriId = uriId, userId = users(0).id.get, source = BookmarkSource("test")))
          }
        }
        val graph = mkURIGraph()
        graph.update() === 1

        setConnections(Map(users(0).id.get -> Set()))

        val searcher = graph.getURIGraphSearcher(users(0).id)

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
