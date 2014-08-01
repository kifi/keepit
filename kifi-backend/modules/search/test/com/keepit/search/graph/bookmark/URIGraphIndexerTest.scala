package com.keepit.search.graph.bookmark

import com.keepit.scraper.FakeArticleStore
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db._
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.TermQuery
import scala.collection.JavaConversions._
import com.keepit.search.index.VolatileIndexDirectory
import com.keepit.search.graph.BaseGraphSearcher
import com.keepit.search.graph.GraphTestHelper

class URIGraphIndexerTest extends Specification with GraphTestHelper {

  "URIGraphIndexer" should {
    "maintain a sequence number on bookmarks " in {
      running(new SearchApplication()) {
        val (users, uris) = initData
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
        val bookmarks = saveBookmarksByURI(expectedUriToUserEdges)

        val bookmarkStoreDir = new VolatileIndexDirectory()
        val uriGraphDir = new VolatileIndexDirectory()
        val indexer = mkURIGraphIndexer(uriGraphDir, bookmarkStoreDir)
        indexer.update() === users.size
        indexer.sequenceNumber.value === bookmarks.size
        indexer.numDocs === users.size
        indexer.close()

        val indexer2 = mkURIGraphIndexer(uriGraphDir, bookmarkStoreDir)
        indexer2.sequenceNumber.value === bookmarks.size
      }
    }

    "find users by uri" in {
      running(new SearchApplication()) {
        val (users, uris) = initData
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val indexer = mkURIGraphIndexer()

        saveBookmarksByURI(expectedUriToUserEdges)

        indexer.update()
        indexer.numDocs === users.size

        val searcher = indexer.getSearcher

        expectedUriToUserEdges.forall {
          case (uri, users) =>
            var hits = Set.empty[Long]
            searcher.doSearch(new TermQuery(new Term(URIGraphFields.uriField, uri.id.get.toString))) { (scorer, reader) =>
              val mapper = reader.getIdMapper
              var doc = scorer.nextDoc()
              while (doc != NO_MORE_DOCS) {
                hits += mapper.getId(doc)
                doc = scorer.nextDoc()
              }
            }
            hits === users.map { _.id.get.id }.toSet
            true
        } === true
      }
    }

    "store user to keep associations in URILists" in {
      running(new SearchApplication()) {
        val (users, uris) = initData

        val indexer = mkURIGraphIndexer()

        val expectedUriToUsers = uris.map { uri => (uri, users.filter { _.id.get.id <= uri.id.get.id }) }
        saveBookmarksByURI(expectedUriToUsers, true)

        indexer.update()
        indexer.numDocs === users.size

        val searcher = new BaseGraphSearcher(indexer.getSearcher)

        users.forall { user =>
          val bookmarks = getBookmarksByUser(user.id.get)

          val publicUriList = searcher.getURIList(URIGraphFields.publicListField, searcher.getDocId(user.id.get.id))
          publicUriList.ids.toSet === bookmarks.filterNot { _.isPrivate }.map { _.uriId.id }.toSet

          val privateUriList = searcher.getURIList(URIGraphFields.privateListField, searcher.getDocId(user.id.get.id))
          privateUriList.ids.toSet === bookmarks.filter { _.isPrivate }.map { _.uriId.id }.toSet

          true
        } === true
      }
    }

    "dump Lucene Document" in {
      running(new SearchApplication()) {
        val store = new FakeArticleStore()

        val Seq(user) = saveUsers(User(firstName = "Agrajag", lastName = ""))
        val uris = saveURIs(
          NormalizedURI.withHash(title = Some("title"), normalizedUrl = "http://www.keepit.com/article1", state = SCRAPED),
          NormalizedURI.withHash(title = Some("title"), normalizedUrl = "http://www.keepit.com/article2", state = SCRAPED)
        )
        saveBookmarksByUser(Seq((user, uris)), uniqueTitle = Some("line1 titles"))

        uris.foreach { uri => store += (uri.id.get -> mkArticle(uri.id.get, "title", "content")) }

        val indexer = mkURIGraphIndexer()
        indexer.update()

        val doc = indexer.buildIndexable(user.id.get, SequenceNumber.ZERO).buildDocument
        doc.getFields.forall { f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
