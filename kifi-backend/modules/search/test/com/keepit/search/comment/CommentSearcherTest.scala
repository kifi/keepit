package com.keepit.search.comment

import com.keepit.search.index.{Searcher, WrappedIndexReader, WrappedSubReader}
import com.keepit.model._
import com.keepit.model.CommentStates._
import com.keepit.common.db._
import com.keepit.search.graph.GraphTestHelper
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable._
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.Version
import scala.collection.JavaConversions._

class CommentSearcherTest extends Specification with GraphTestHelper {

  import CommentFields._

  def mkCommentStore(commentStoreDir: RAMDirectory = new RAMDirectory): CommentStore = {
    new CommentStore(commentStoreDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[ShoeboxServiceClient])
  }
  def mkCommentIndexer(commentDir: RAMDirectory = new RAMDirectory, commentStore: CommentStore = mkCommentStore()): CommentIndexer = {
    new CommentIndexer(commentDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), commentStore, inject[ShoeboxServiceClient])
  }

  "CommentSearcher" should {
    "return hits" in {
      running(new EmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val commentIndexer = mkCommentIndexer()

        val parent1 = saveComment(
          Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is a message",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE
          ),
          users(1).id.get
        )

        val parent2 = saveComment(
          Comment(
            uriId = uris(1).id.get,
            userId = users(1).id.get,
            text = "this is a message",
            pageTitle = uris(1).title.get,
            permissions = CommentPermissions.MESSAGE
          ),
          users(2).id.get
        )
        commentIndexer.update()
        commentIndexer.numDocs === 2

        val query = new TermQuery(new Term(textField, "message"))

        var searcher = new CommentSearcher(commentIndexer.getSearcher)

        val res1 = searcher.search(users(0).id.get, query, 10)
        res1.hits.size === 1
        res1.hits(0).id === parent1.id.get.id

        val res2 = searcher.search(users(1).id.get, query, 10)
        res2.hits.size === 2
        Set(res2.hits(0).id, res2.hits(1).id) === Set(parent1.id.get.id, parent2.id.get.id)

        val res3 = searcher.search(users(2).id.get, query, 10)
        res3.hits.size === 1
        res3.hits(0).id === parent2.id.get.id
      }
    }

    "return hits in time descending order" in {
      running(new EmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val commentIndexer = mkCommentIndexer()

        val parent1 = saveComment(
          Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is a message",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE
          ),
          users(1).id.get
        )

        val parent2 = saveComment(
          Comment(
            uriId = uris(1).id.get,
            userId = users(1).id.get,
            text = "this is a message",
            pageTitle = uris(1).title.get,
            permissions = CommentPermissions.MESSAGE
          ),
          users(2).id.get
        )
        commentIndexer.update()
        commentIndexer.numDocs === 2

        val query = new TermQuery(new Term(textField, "message"))

        var searcher = new CommentSearcher(commentIndexer.getSearcher)
        var res = searcher.search(users(1).id.get, query, 10)
        res.hits.size === 2
        res.hits(0).id === parent2.id.get.id
        res.hits(1).id === parent1.id.get.id

        val reply1 = saveComment(
          Comment(
            uriId = uris(0).id.get,
            userId = users(1).id.get,
            text = "this is a reply",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE,
            parent = parent1.id
          )
        )

        commentIndexer.update()

        searcher = new CommentSearcher(commentIndexer.getSearcher)

        res = searcher.search(users(1).id.get, query, 10)
        res.hits.size === 2
        res.hits(0).id === parent1.id.get.id
        res.hits(1).id === parent2.id.get.id
      }
    }

    "return maxHits and paginate" in {
      running(new EmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val commentIndexer = mkCommentIndexer()

        val parents = uris.map{ uri =>
          saveComment(
            Comment(
              uriId = uri.id.get,
              userId = users(0).id.get,
              text = "this is a message",
              pageTitle = uri.title.get,
              permissions = CommentPermissions.MESSAGE
            )
          )
        }

        commentIndexer.update()
        commentIndexer.numDocs === parents.size

        val query = new TermQuery(new Term(textField, "message"))
        val maxHits = (parents.size + 1)/ 2

        var searcher = new CommentSearcher(commentIndexer.getSearcher)

        var res = searcher.search(users(0).id.get, query, maxHits)
        res.hits.size === maxHits
        res.hits(0).id === parents.last.id.get.id

        val idFilter = res.hits.map(_.id).toSet

        res = searcher.search(users(0).id.get, query, maxHits, idFilter)
        res.hits.size === (parents.size - maxHits)

        val allIds = idFilter ++ res.hits.map(_.id).toSet
        allIds === parents.map(_.id.get.id).toSet
      }
    }
  }
}
