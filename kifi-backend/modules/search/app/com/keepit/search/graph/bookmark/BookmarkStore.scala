package com.keepit.search.graph.bookmark

import com.keepit.common.db._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.model._
import com.keepit.model.KeepStates._
import com.keepit.search.index._
import java.io.StringReader
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
  def shouldDelete(bookmark: Keep, shard: Shard[NormalizedURI]): Boolean = ((bookmark.state == INACTIVE) || (!shard.contains(bookmark.uriId)))
  val bookmarkSource = KeepSource("BookmarkStore")
}

class BookmarkStore(
  indexDirectory: IndexDirectory,
  val airbrake: AirbrakeNotifier)
    extends Indexer[Keep, Keep, BookmarkStore](indexDirectory) {

  import BookmarkStoreFields._

  override def onFailure(indexable: Indexable[Keep, Keep], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = throw new UnsupportedOperationException("BookmarkStore should not be updated by update()")

  def update(name: String, bookmarks: Seq[Keep], shard: Shard[NormalizedURI]) {
    doUpdate("BookmarkStore" + name) {
      bookmarks.iterator.map(buildIndexable(_, shard))
    }
  }

  def getBookmarks(userId: Id[User]): Seq[Keep] = {
    val term = new Term(userField, userId.id.toString)
    val buf = new ArrayBuffer[Keep]
    getSearcher.foreachReader { reader =>
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
          buf += Keep(
            id = Some(Id[Keep](bookmarkId)),
            title = Some(rec.title),
            url = rec.url,
            urlId = Id[URL](-1),
            createdAt = new DateTime(rec.createdAt),
            uriId = rec.uriId,
            isPrivate = rec.isPrivate,
            userId = userId,
            source = BookmarkStore.bookmarkSource,
            libraryId = None // todo(andrew/léo/yasuhiro): Fix to include real library
          )
        }
      }
    }
    buf
  }

  def getBookmarkRecord(bookmarkId: Long): Option[BookmarkRecord] = {
    getSearcher.findDocIdAndAtomicReaderContext(bookmarkId).flatMap {
      case (docid, context) =>
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

  def buildIndexable(bookmark: Keep, shard: Shard[NormalizedURI]): BookmarkIndexable = {
    new BookmarkIndexable(
      id = bookmark.id.get,
      sequenceNumber = bookmark.seq,
      isDeleted = BookmarkStore.shouldDelete(bookmark, shard),
      bookmark = bookmark)
  }

  class BookmarkIndexable(
      override val id: Id[Keep],
      override val sequenceNumber: SequenceNumber[Keep],
      override val isDeleted: Boolean,
      val bookmark: Keep) extends Indexable[Keep, Keep] {

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
