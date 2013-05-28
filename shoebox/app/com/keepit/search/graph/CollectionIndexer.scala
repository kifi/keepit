package com.keepit.search.graph

import scala.collection.mutable.ArrayBuffer
import java.io.StringReader
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.BinaryDocValuesField
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.Version
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder
import com.keepit.search.index.{DefaultAnalyzer, Indexable, Indexer, IndexError}
import com.keepit.search.index.Indexable.IteratorTokenStream
import com.keepit.search.line.LineField
import com.keepit.search.line.LineFieldBuilder
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._
import scala.concurrent.Await

object CollectionFields {
  val userField = "coll_usr"
  val uriField = "coll_uri"
  val uriListField = "coll_list"

  def decoders() = Map(
    uriListField -> DocUtil.URIListDecoder
  )
}

class CollectionIndexer(
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[Collection](indexDirectory, indexWriterConfig, CollectionFields.decoders) {

  val commitBatchSize = 100
  val fetchSize = commitBatchSize * 3

  private def commitCallback(commitBatch: Seq[(Indexable[Collection], Option[IndexError])]) = {
    var cnt = 0
    commitBatch.foreach{ case (indexable, indexError) =>
      indexError match {
        case Some(error) =>
          log.error("indexing failed for user=%s error=%s".format(indexable.id, error.msg))
        case None =>
          cnt += 1
      }
    }
    cnt
  }

  def update(): Int = {
    resetSequenceNumberIfReindex()
    update {
      Await.result(shoeboxClient.getCollectionsChanged(sequenceNumber), 180 seconds)
    }
  }

  def update(userId: Id[User]): Int = {
    deleteDocuments(new Term(CollectionFields.userField, userId.toString), doCommit = false)
    update {
      Await.result(shoeboxClient.getCollectionsByUser(userId), 180 seconds).map{ collectionId => (collectionId, userId, SequenceNumber.MinValue) }
    }
  }

  private def update(collectionsChanged: => Seq[(Id[Collection], Id[User], SequenceNumber)]): Int = {
    log.info("updating Collection")
    try {
      var cnt = 0
      indexDocuments(collectionsChanged.iterator.map(buildIndexable), commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
      cnt
    } catch { case e: Throwable =>
      log.error("error in Collection update", e)
      throw e
    }
  }

  def buildIndexable(collectionIdAndSequenceNumber: (Id[Collection], Id[User], SequenceNumber)): CollectionListIndexable = {
    val (collectionId, userId, seq) = collectionIdAndSequenceNumber
    val bookmarks = Await.result(shoeboxClient.getBookmarksInCollection(collectionId), 180 seconds)
    new CollectionListIndexable(id = collectionId,
                                sequenceNumber = seq,
                                userId = userId,
                                bookmarks = bookmarks)
  }

  class CollectionListIndexable(
    override val id: Id[Collection],
    override val sequenceNumber: SequenceNumber,
    val userId: Id[User],
    val bookmarks: Seq[Bookmark]
  ) extends Indexable[Collection] {

    override val isDeleted: Boolean = bookmarks.isEmpty
    override def buildDocument = {
      val doc = super.buildDocument

      val collListBytes = URIList.toByteArray(bookmarks)
      val collListField = buildURIListField(CollectionFields.uriListField, collListBytes)
      val collList = URIList(collListBytes)
      doc.add(collListField)

      val uri = buildURIIdField(collList)
      doc.add(uri)

      val user = buildKeywordField(CollectionFields.userField, userId.toString)
      doc.add(user)

      doc
    }

    private def buildURIListField(field: String, uriListBytes: Array[Byte]) = {
      new BinaryDocValuesField(field, new BytesRef(uriListBytes))
    }

    private def buildURIIdField(uriList: URIList) = {
      buildIteratorField(CollectionFields.uriField, uriList.ids.iterator){ uriId => uriId.toString }
    }
  }
}

