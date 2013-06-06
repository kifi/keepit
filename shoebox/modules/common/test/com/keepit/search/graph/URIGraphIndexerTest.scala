package com.keepit.search.graph

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.{Article, ArticleStore, Lang}
import com.keepit.search.index.{Searcher, WrappedIndexReader, WrappedSubReader}
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
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
import scala.collection.JavaConversions._

class URIGraphIndexerTest extends Specification with GraphTestHelper {

  "URIGraphIndexer" should {
    "maintain a sequence number on bookmarks " in {
      running(new DevApplication().withShoeboxServiceModule)
            {
        val (users, uris) = setupDB
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = mkBookmarks(expectedUriToUserEdges)

        val dir = new RAMDirectory
        val indexer = mkURIGraphIndexer(dir)
        indexer.update() === users.size
        indexer.sequenceNumber.value === bookmarks.size
        indexer.numDocs === users.size
        indexer.close()

        val indexer2 = mkURIGraphIndexer(dir)
        indexer2.sequenceNumber.value === bookmarks.size
      }
    }

    "find users by uri" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val indexer = mkURIGraphIndexer()

        mkBookmarks(expectedUriToUserEdges)

        indexer.update()
        indexer.numDocs === users.size

        val searcher = indexer.getSearcher

        expectedUriToUserEdges.forall{ case (uri, users) =>
          var hits = Set.empty[Long]
          searcher.doSearch(new TermQuery(new Term(URIGraphFields.uriField, uri.id.get.toString))){ (scorer, mapper) =>
            var doc = scorer.nextDoc()
            while (doc != NO_MORE_DOCS) {
              hits += mapper.getId(doc)
              doc = scorer.nextDoc()
            }
          }
          hits === users.map{ _.id.get.id }.toSet
          true
        } === true
      }
    }

    "store user to keep associations in URILists" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val indexer = mkURIGraphIndexer()

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter{ _.id.get.id <= uri.id.get.id }) }
        mkBookmarks(expectedUriToUsers, true)

        indexer.update()
        indexer.numDocs === users.size

        val searcher = new BaseGraphSearcher(indexer.getSearcher)

        users.forall{ user =>
          val bookmarks = db.readOnly { implicit s => bookmarkRepo.getByUser(user.id.get) }

          val publicUriList = searcher.getURIList(URIGraphFields.publicListField, searcher.getDocId(user.id.get.id))
          publicUriList.ids.toSet === bookmarks.filterNot{ _.isPrivate }.map{ _.uriId.id }.toSet

          val privateUriList = searcher.getURIList(URIGraphFields.privateListField, searcher.getDocId(user.id.get.id))
          privateUriList.ids.toSet === bookmarks.filter{ _.isPrivate }.map{ _.uriId.id }.toSet

          true
        } === true
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

        val indexer = mkURIGraphIndexer()
        val doc = indexer.buildIndexable(user.id.get, SequenceNumber.ZERO).buildDocument
        doc.getFields.forall{ f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
