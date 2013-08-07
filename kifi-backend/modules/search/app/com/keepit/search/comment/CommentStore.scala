package com.keepit.search.comment

import com.keepit.common.db._
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
import com.keepit.model._
import com.keepit.model.CommentStates._
import com.keepit.search.Lang
import com.keepit.search.index.{DefaultAnalyzer, FieldDecoder, Indexable, Indexer}
import java.io.StringReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import com.google.inject.Inject
import CommentRecordSerializer._
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.util.BytesRef
import org.joda.time.DateTime
import com.keepit.shoebox.ShoeboxServiceClient


object CommentStoreFields {
  val parentField = "cs_parent"
  val recField = "cs_rec"
  def decoders() = Map.empty[String, FieldDecoder]
}

object CommentStore {
  def shouldDelete(comment: Comment): Boolean = (comment.state == INACTIVE)
}

class CommentStore @Inject() (
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    healthcheckPlugin: HealthcheckPlugin,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[Comment](indexDirectory, indexWriterConfig) {

  import CommentStoreFields._

  private[this] val commitBatchSize = 3000

  override def onFailure(indexable: Indexable[Comment], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    healthcheckPlugin.addError(HealthcheckError(errorMessage = Some(msg), callType = INTERNAL))
    super.onFailure(indexable, e)
  }

  def update(comments: Seq[Comment]) {
    try {
      val cnt = successCount
      indexDocuments(comments.iterator.map(buildIndexable), commitBatchSize)
      successCount - cnt
    } catch { case e: Throwable =>
      log.error("error in CommentStore update", e)
      throw e
    }
  }

  def getComments(parent: Id[Comment]): Seq[Comment] = {
    val term = new Term(parentField, parent.id.toString)
    val buf = new ArrayBuffer[Comment]
    getSearcher.foreachReader{ reader =>
      val mapper = reader.getIdMapper
      val td = reader.termDocsEnum(term)
      if (td != null) {
        val docValues = reader.getBinaryDocValues(recField)
        while (td.nextDoc < NO_MORE_DOCS) {
          val docid = td.docID
          var ref = new BytesRef()
          docValues.get(td.docID, ref)
          val rec = CommentRecordSerializer.fromByteArray(ref.bytes, ref.offset, ref.length)
          val commentId = mapper.getId(docid)
          buf += Comment(
            id = Some(Id[Comment](commentId)),
            createdAt = new DateTime(rec.createdAt),
            uriId = rec.uriId,
            userId = rec.userId,
            text = rec.text,
            pageTitle = rec.pageTitle,
            parent = Some(parent),
            permissions = rec.permission,
            externalId = rec.externalId
          )
        }
      }
    }
    buf.sortBy(_.createdAt.getMillis)
  }

  def getCommentRecord(commentId: Long): Option[CommentRecord] = {
    getSearcher.findDocIdAndAtomicReaderContext(commentId).flatMap{ case (docid, context) =>
      val reader = context.reader
      val recValues = reader.getBinaryDocValues(recField)
      if (recValues != null) {
        var ref = new BytesRef()
        recValues.get(docid, ref)
        Some(CommentRecordSerializer.fromByteArray(ref.bytes, ref.offset, ref.length))
      } else {
        None
      }
    }
  }

  def buildIndexable(comment: Comment): CommentIndexable = {
    new CommentIndexable(
      id = comment.id.get,
      sequenceNumber = comment.seq,
      isDeleted = CommentStore.shouldDelete(comment),
      comment = comment)
  }

  class CommentIndexable(
    override val id: Id[Comment],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val comment: Comment
  ) extends Indexable[Comment] {

    implicit def toReader(text: String) = new StringReader(text)

    override def buildDocument = {
      val doc = super.buildDocument

      val parent = comment.parent.getOrElse(comment.id.get)
      doc.add(buildKeywordField(parentField, parent.id.toString))

      // save comment information (title, url, createdAt) in the store
      val r = CommentRecord(
        comment.text,
        comment.createdAt.getMillis,
        comment.userId,
        comment.uriId,
        comment.pageTitle,
        comment.permissions,
        comment.externalId)

      doc.add(buildBinaryDocValuesField(recField, r))

      doc
    }
  }
}
