package com.keepit.search.graph.bookmark

import com.keepit.common.db._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.model._
import com.keepit.model.BookmarkStates._
import com.keepit.search.index._
import java.io.StringReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import com.google.inject.Inject
import BookmarkRecordSerializer._
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.util.BytesRef
import org.joda.time.DateTime
import com.keepit.search.sharding.Shard


object BookmarkStoreFields {
  val userField = "bm_usr"
  val recField = "bm_rec"
  def decoders() = Map.empty[String, FieldDecoder]
}

object BookmarkStore {
  def shouldDelete(bookmark: Bookmark, shard: Shard[NormalizedURI]): Boolean = ((bookmark.state == INACTIVE) || (!shard.contains(bookmark.uriId)))
  val bookmarkSource = BookmarkSource("BookmarkStore")
}

class BookmarkStore(
    indexDirectory: IndexDirectory,
    indexWriterConfig: IndexWriterConfig,
    airbrake: AirbrakeNotifier)
  extends Indexer[Bookmark, Bookmark, BookmarkStore](indexDirectory, indexWriterConfig) {

  import BookmarkStoreFields._

  override def onFailure(indexable: Indexable[Bookmark, Bookmark], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = throw new UnsupportedOperationException("BookmarkStore should not be updated by update()")

  def update(name: String, bookmarks: Seq[Bookmark], shard: Shard[NormalizedURI]) {
    doUpdate("BookmarkStore" + name){
      bookmarks.iterator.map(buildIndexable(_, shard))
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
            uriId = rec.uriId,
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

  def buildIndexable(bookmark: Bookmark, shard: Shard[NormalizedURI]): BookmarkIndexable = {
    new BookmarkIndexable(
      id = bookmark.id.get,
      sequenceNumber = bookmark.seq,
      isDeleted = BookmarkStore.shouldDelete(bookmark, shard),
      bookmark = bookmark)
  }

  class BookmarkIndexable(
    override val id: Id[Bookmark],
    override val sequenceNumber: SequenceNumber[Bookmark],
    override val isDeleted: Boolean,
    val bookmark: Bookmark
  ) extends Indexable[Bookmark, Bookmark] {

    implicit def toReader(text: String) = new StringReader(text)

    override def buildDocument = {
      val doc = super.buildDocument

      val user = bookmark.userId
      doc.add(buildKeywordField(userField, user.id.toString))

      // save bookmark information (title, url, createdAt) in the store
      val r = BookmarkRecord(
        bookmark.title.getOrElse(""),
        bookmark.url,
        bookmark.createdAt.getMillis,
        bookmark.isPrivate,
        bookmark.uriId,
        Some(bookmark.externalId))

      doc.add(buildBinaryDocValuesField(recField, r))

      doc
    }
  }
}
