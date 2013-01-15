package com.keepit.search.graph

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.ConditionalQuery
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db.{Id, CX}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.BooleanQuery
import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class URIGraphTest extends SpecificationWithJUnit {

  private def setupDB = {
    CX.withConnection { implicit c =>
      (List(User(firstName = "Agrajag", lastName = "").save,
            User(firstName = "Barmen", lastName = "").save,
            User(firstName = "Colin", lastName = "").save,
            User(firstName = "Dan", lastName = "").save,
            User(firstName = "Eccentrica", lastName = "").save,
            User(firstName = "Hactar", lastName = "").save),
       List(NormalizedURIFactory(title = "a1", url = "http://www.keepit.com/article1", state=SCRAPED).save,
            NormalizedURIFactory(title = "a2", url = "http://www.keepit.com/article2", state=SCRAPED).save,
            NormalizedURIFactory(title = "a3", url = "http://www.keepit.org/article3", state=SCRAPED).save,
            NormalizedURIFactory(title = "a4", url = "http://www.findit.com/article4", state=SCRAPED).save,
            NormalizedURIFactory(title = "a5", url = "http://www.findit.com/article5", state=SCRAPED).save,
            NormalizedURIFactory(title = "a6", url = "http://www.findit.org/article6", state=SCRAPED).save))
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

  "URIGraph" should {
    "be able to generate UriToUsrEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = URLCxRepo.get(uri.url).getOrElse(URL(uri.url, uri.id.get).save)
              BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")).save
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

    "be able to generate UserToUriEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = URLCxRepo.get(uri.url).getOrElse(URL(uri.url, uri.id.get).save)
              BookmarkFactory(title = uri.title.get, url = url1, uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")).save
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

    "be able to intersect UserToUserEdgeSet and UriToUserEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = URLCxRepo.get(uri.url).getOrElse(URL(uri.url, uri.id.get).save)
              BookmarkFactory(title = uri.title.get, url = url1, uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")).save
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

      "handle empty sets" in {
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
    }

    "search personal bookmark titles" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)

        CX.withConnection { implicit c =>
          uris.foreach{ uri =>
            val uriId =  uri.id.get
            val url1 = URLCxRepo.get(uri.url).getOrElse(URL(uri.url, uri.id.get).save)
            BookmarkFactory(title = ("personaltitle bmt"+uriId), url = url1,  uriId = uriId, userId = users((uriId.id % 2L).toInt).id.get, source = BookmarkSource("test")).save
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

        CX.withConnection { implicit c =>
          uris.foreach{ uri =>
            val uriId =  uri.id.get
            val url1 = URLCxRepo.get(uri.url).getOrElse(URL(uri.url, uri.id.get).save)
            BookmarkFactory(title = ("personaltitle bmt"+uriId), url = url1,  uriId = uriId, userId = users(0).id.get, source = BookmarkSource("test")).save
          }
        }

        CX.withConnection { implicit conn =>
          println(BookmarkCxRepo.all)
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

    "be able to dump Lucene Document" in {
      running(new EmptyApplication()) {
        val ramDir = new RAMDirectory
        val store = new FakeArticleStore()

        val (user, uris, bookmarks) = CX.withConnection { implicit c =>
          val user = User(firstName = "Agrajag", lastName = "").save
          val uris = Array(
            NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article1", state=SCRAPED).save,
            NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article2", state=SCRAPED).save
          )

          val url1 = URL(uris(0).url, uris(0).id.get).save
          val url2 = URL(uris(1).url, uris(1).id.get).save

          val bookmarks = Array(
            BookmarkFactory(title = "line1 titles", url = url1,  uriId = uris(0).id.get, userId = user.id.get, source = BookmarkSource("test")).save,
            BookmarkFactory(title = "line2 titles", url = url2,  uriId = uris(1).id.get, userId = user.id.get, source = BookmarkSource("test")).save
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
