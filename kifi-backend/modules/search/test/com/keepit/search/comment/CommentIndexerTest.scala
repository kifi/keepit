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

class CommentIndexerTest extends Specification with GraphTestHelper {

  def mkCommentStore(commentStoreDir: RAMDirectory = new RAMDirectory): CommentStore = {
    new CommentStore(commentStoreDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[ShoeboxServiceClient])
  }
  def mkCommentIndexer(commentDir: RAMDirectory = new RAMDirectory, commentStore: CommentStore = mkCommentStore()): CommentIndexer = {
    new CommentIndexer(commentDir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), commentStore, inject[ShoeboxServiceClient])
  }

  "CommentIndexer" should {
    "maintain a sequence number on comments " in {
      running(new EmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val numURIs = uris.size

        val commentStoreDir = new RAMDirectory
        val commentStore = mkCommentStore(commentStoreDir)
        val commentDir = new RAMDirectory
        val commentIndexer = mkCommentIndexer(commentDir, commentStore)

        val publicComment = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is a comment",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.PUBLIC
          ))
        }
        val parent = db.readWrite { implicit s =>
          val parent = commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is a comment",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE
          ))
          commentRecipientRepo.save(CommentRecipient(
            commentId = parent.id.get,
            userId = users(1).id
          ))
          parent
        }

        commentIndexer.update() === 2 // two message threads created
        commentIndexer.sequenceNumber.value === 2
        commentIndexer.numDocs === 2 // total two message threads

        val reply1 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(1).id.get,
            text = "this is a reply",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE,
            parent = parent.id
          ))
        }
        val reply2 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is another reply",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE,
            parent = parent.id
          ))
        }

        commentIndexer.update() === 1 // one message thread udpated
        commentIndexer.sequenceNumber.value === 4
        commentIndexer.numDocs === 2 // total two message threads
        commentIndexer.close()

        val commentIndexer2 = mkCommentIndexer(commentDir, commentStore)
        commentIndexer2.sequenceNumber.value === 4
        commentIndexer2.numDocs === 2 // total two message threads
      }
    }

    "find messages" in {
      running(new EmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val commentIndexer = mkCommentIndexer()

        val parent1 = db.readWrite { implicit s =>
          val parent = commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is the firstmessage",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE
          ))
          commentRecipientRepo.save(CommentRecipient(
            commentId = parent.id.get,
            userId = users(1).id
          ))
          parent
        }

        val parent2 = db.readWrite { implicit s =>
          val parent = commentRepo.save(Comment(
            uriId = uris(1).id.get,
            userId = users(1).id.get,
            text = "this is the secondmessage",
            pageTitle = uris(1).title.get,
            permissions = CommentPermissions.MESSAGE
          ))
          commentRecipientRepo.save(CommentRecipient(
            commentId = parent.id.get,
            userId = users(2).id
          ))
          parent
        }
        commentIndexer.update()
        commentIndexer.numDocs === 2

        var searcher = commentIndexer.getSearcher

        // find by user id
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.participantIdField, users(0).id.get.id.toString)))
          hits1.size === 1
          hits1(0).id === parent1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.participantIdField, users(1).id.get.id.toString)))
          hits2.size === 2
          hits2.map(_.id).toSet === Set(parent1.id.get.id, parent2.id.get.id)

          val hits3 = searcher.search(new TermQuery(new Term(CommentFields.participantIdField, users(2).id.get.id.toString)))
          hits3.size === 1
          hits3(0).id === parent2.id.get.id
        }

        // find by user name
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.participantNameField, users(0).firstName.toLowerCase)))
          hits1.size === 1
          hits1(0).id === parent1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.participantNameField, users(1).firstName.toLowerCase)))
          hits2.size === 2
          hits2.map(_.id).toSet === Set(parent1.id.get.id, parent2.id.get.id)

          val hits3 = searcher.search(new TermQuery(new Term(CommentFields.participantNameField, users(2).firstName.toLowerCase)))
          hits3.size === 1
          hits3(0).id === parent2.id.get.id
        }

        // find by page title
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.titleField, uris(0).title.get)))
          hits1.size === 1
          hits1(0).id === parent1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.titleField, uris(1).title.get)))
          hits2.size === 1
          hits2(0).id === parent2.id.get.id
        }

        // find by comment text
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.textField, "firstmessage")))
          hits1.size === 1
          hits1(0).id === parent1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.textField, "secondmessage")))
          hits2.size === 1
          hits2(0).id === parent2.id.get.id
        }

        // find by site
        {
          val hits = searcher.search(new TermQuery(new Term(CommentFields.siteField, "keepit.com")))
          hits.size === 2
        }


        val reply1 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(1).id.get,
            text = "this is the firstreply",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE,
            parent = parent1.id
          ))
        }
        val reply2 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(1).id.get,
            userId = users(0).id.get,
            text = "this is the secondreply",
            pageTitle = uris(1).title.get,
            permissions = CommentPermissions.MESSAGE,
            parent = parent2.id
          ))
        }
        commentIndexer.update() === 2
        commentIndexer.numDocs === 2

        searcher = commentIndexer.getSearcher

        // find by comment text
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.textField, "firstmessage")))
          hits1.size === 1
          hits1(0).id === parent1.id.get.id

          val hits1r = searcher.search(new TermQuery(new Term(CommentFields.textField, "firstreply")))
          hits1r.size === 1
          hits1r(0).id === parent1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.textField, "secondmessage")))
          hits2.size === 1
          hits2(0).id === parent2.id.get.id

          val hits2r = searcher.search(new TermQuery(new Term(CommentFields.textField, "secondreply")))
          hits2r.size === 1
          hits2r(0).id === parent2.id.get.id
        }
      }
    }

    "get comment time stamps" in {
      running(new EmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val commentIndexer = mkCommentIndexer()

        val parent1 = db.readWrite { implicit s =>
          val parent = commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is the firstmessage",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE
          ))
          commentRecipientRepo.save(CommentRecipient(
            commentId = parent.id.get,
            userId = users(1).id
          ))
          parent
        }

        val publicComment1 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is the firstcomment",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.PUBLIC
          ))
        }

        commentIndexer.update()
        commentIndexer.numDocs === 2

        var searcher = commentIndexer.getSearcher
        searcher.getLongDocValue(CommentFields.timestampField, parent1.id.get.id) === Some(parent1.createdAt.getMillis)
        searcher.getLongDocValue(CommentFields.timestampField, publicComment1.id.get.id) === Some(publicComment1.createdAt.getMillis)

        val reply1 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(1).id.get,
            text = "this is the firstreply",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE,
            parent = parent1.id
          ))
        }
        commentIndexer.update()
        commentIndexer.numDocs === 2

        searcher = commentIndexer.getSearcher
        searcher.getLongDocValue(CommentFields.timestampField, parent1.id.get.id) === Some(reply1.createdAt.getMillis)

        val reply2 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is the secondreply",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.MESSAGE,
            parent = parent1.id
          ))
        }
        commentIndexer.update()
        commentIndexer.numDocs === 2

        searcher = commentIndexer.getSearcher
        searcher.getLongDocValue(CommentFields.timestampField, parent1.id.get.id) === Some(reply2.createdAt.getMillis)
      }
    }

    "find public comments" in {
      running(new EmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val commentIndexer = mkCommentIndexer()

        val publicComment1 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(0).id.get,
            userId = users(0).id.get,
            text = "this is the firstcomment",
            pageTitle = uris(0).title.get,
            permissions = CommentPermissions.PUBLIC
          ))
        }
        val publicComment2 = db.readWrite { implicit s =>
          commentRepo.save(Comment(
            uriId = uris(1).id.get,
            userId = users(1).id.get,
            text = "this is the secondcomment",
            pageTitle = uris(1).title.get,
            permissions = CommentPermissions.PUBLIC
          ))
        }

        commentIndexer.update()
        commentIndexer.numDocs === 2

        val searcher = commentIndexer.getSearcher

        // find by user id
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.participantIdField, users(0).id.get.id.toString)))
          hits1.size === 1
          hits1(0).id === publicComment1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.participantIdField, users(1).id.get.id.toString)))
          hits2.size === 1
          hits2(0).id === publicComment2.id.get.id
        }

        // find by user name
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.participantNameField, users(0).firstName.toLowerCase)))
          hits1.size === 1
          hits1(0).id === publicComment1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.participantNameField, users(1).firstName.toLowerCase)))
          hits2.size === 1
          hits2(0).id === publicComment2.id.get.id
        }

        // find by page title
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.titleField, uris(0).title.get)))
          hits1.size === 1
          hits1(0).id === publicComment1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.titleField, uris(1).title.get)))
          hits2.size === 1
          hits2(0).id === publicComment2.id.get.id
        }

        // find by comment text
        {
          val hits1 = searcher.search(new TermQuery(new Term(CommentFields.textField, "firstcomment")))
          hits1.size === 1
          hits1(0).id === publicComment1.id.get.id

          val hits2 = searcher.search(new TermQuery(new Term(CommentFields.textField, "secondcomment")))
          hits2.size === 1
          hits2(0).id === publicComment2.id.get.id
        }

        // find by site
        {
          val hits = searcher.search(new TermQuery(new Term(CommentFields.siteField, "keepit.com")))
          hits.size === 2
        }
      }
    }
  }
}
