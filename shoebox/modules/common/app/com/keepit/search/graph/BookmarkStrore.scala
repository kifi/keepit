package com.keepit.search.graph

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.model.BookmarkStates._
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.search.index.{DefaultAnalyzer, FieldDecoder, Indexable, Indexer, IndexError}
import java.io.StringReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import com.google.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import BookmarkRecordSerializer._
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.util.BytesRef
import com.keepit.search.query.QueryUtil
import org.joda.time.DateTime


object BookmarkStoreFields {
  val userField = "bm_usr"
  val recField = "bm_rec"
  def decoders() = Map.empty[String, FieldDecoder]
}

object BookmarkStore {
  def shouldDelete(bookmark: Bookmark): Boolean = (bookmark.state == INACTIVE)
  val bookmarkSource = BookmarkSource("BookmarkStore")
}

class BookmarkStore @Inject() (
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig)
  extends Indexer[Bookmark](indexDirectory, indexWriterConfig) {

  import BookmarkStoreFields._

  private[this] val commitBatchSize = 3000

  private def commitCallback(commitBatch: Seq[(Indexable[Bookmark], Option[IndexError])]) = {
    var cnt = 0
    commitBatch.foreach{ case (indexable, indexError) =>
      indexError match {
        case Some(error) =>
          log.error("indexing failed for bookmark=%s error=%s".format(indexable.id, error.msg))
        case None =>
          cnt += 1
      }
    }
    cnt
  }

  def update(bookmarks: Seq[Bookmark]) {
    try {
      var cnt = 0
      indexDocuments(bookmarks.iterator.map(buildIndexable), commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
    } catch { case e: Throwable =>
      log.error("error in BookmarkStore update", e)
      throw e
    }
  }

  def getBookmarks(userId: Id[User]): Seq[Bookmark] = {
    val term = new Term(userField, userId.id.toString)
    val buf = new ArrayBuffer[Bookmark]
    getSearcher.foreachReader{ reader =>
      val mapper = reader.getIdMapper
      val td = reader.termDocsEnum(term)
      if (td != null) {
        val docValues = reader.getBinaryDocValues(recField)
        while (td.nextDoc < NO_MORE_DOCS) {
          val docid = td.docID
          var ref = new BytesRef()
          docValues.get(td.docID, ref)
          val rec = BookmarkRecordSerializer.fromByteArray(ref.bytes, ref.offset, ref.length)
          val bookmarkId = mapper.getId(docid)
          buf += Bookmark(
            id = Some(Id[Bookmark](bookmarkId)),
            title = Some(rec.title),
            url = rec.url,
            createdAt = new DateTime(rec.createdAt),
            uriId = Id[NormalizedURI](rec.uriId),
            isPrivate = rec.isPrivate,
            userId = userId,
            source = BookmarkStore.bookmarkSource
          )
        }
      }
    }
    buf
  }

  def getBookmarkRecord(bookmarkId: Long): Option[BookmarkRecord] = {
    getSearcher.findDocIdAndAtomicReaderContext(bookmarkId).flatMap{ case (docid, context) =>
      val reader = context.reader
      val recValues = reader.getBinaryDocValues(recField)
      if (recValues != null) {
        var ref = new BytesRef()
        recValues.get(docid, ref)
        Some(BookmarkRecordSerializer.fromByteArray(ref.bytes, ref.offset, ref.length))
      } else {
        None
      }
    }
  }

  def buildIndexable(bookmark: Bookmark): BookmarkIndexable = {
    new BookmarkIndexable(id = bookmark.id.get,
                          sequenceNumber = bookmark.seq,
                          isDeleted = BookmarkStore.shouldDelete(bookmark),
                          bookmark = bookmark)
  }

  class BookmarkIndexable(
    override val id: Id[Bookmark],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val bookmark: Bookmark
  ) extends Indexable[Bookmark] {

    implicit def toReader(text: String) = new StringReader(text)

    override def buildDocument = {
      val doc = super.buildDocument

      val user = bookmark.userId
      doc.add(buildKeywordField(userField, user.id.toString))

      // save bookmark information (title, url, createdAt) in the store
      val r = BookmarkRecord(bookmark.uriId.id, bookmark.title.getOrElse(""), bookmark.url, bookmark.createdAt.getMillis, bookmark.isPrivate)
      doc.add(buildBinaryDocValuesField(recField, r))

      doc
    }
  }
}
