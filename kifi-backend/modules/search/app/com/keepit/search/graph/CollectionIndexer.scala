package com.keepit.search.graph

import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.BinaryDocValuesField
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.Version
import com.keepit.common.db._
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.common.strings._
import com.keepit.model._
import com.keepit.model.CollectionStates._
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder
import com.keepit.search.index.{DefaultAnalyzer, Indexable, Indexer}
import com.keepit.search.index.Indexable.IteratorTokenStream
import com.keepit.search.line.LineField
import com.keepit.search.line.LineFieldBuilder
import com.keepit.shoebox.ShoeboxServiceClient
import java.io.StringReader
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.Await

object CollectionFields {
  val userField = "coll_usr"
  val uriField = "coll_uri"
  val uriListField = "coll_list"
  val externalIdField = "col_ext"

  def decoders() = Map(
    uriListField -> DocUtil.URIListDecoder,
    externalIdField -> DocUtil.binaryDocValFieldDecoder(fromByteArray)
  )
}

object CollectionIndexer {
  def shouldDelete(collection: Collection): Boolean = (collection.state == INACTIVE)
  val bookmarkSource = BookmarkSource("BookmarkStore")
}

class CollectionIndexer(
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    healthcheckPlugin: HealthcheckPlugin,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[Collection](indexDirectory, indexWriterConfig, CollectionFields.decoders) {

  import CollectionFields._

  private[this] val commitBatchSize = 100
  private[this] val fetchSize = commitBatchSize * 3

  private[this] val updateLock = new AnyRef

  override def onFailure(indexable: Indexable[Collection], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    healthcheckPlugin.addError(HealthcheckError(errorMessage = Some(msg), callType = INTERNAL))
    super.onFailure(indexable, e)
  }

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      total += update {
        val collections = Await.result(shoeboxClient.getCollectionsChanged(sequenceNumber, fetchSize), 180 seconds)
        done = collections.isEmpty
        collections
      }
    }
    total
  }

  def update(userId: Id[User]): Int = updateLock.synchronized {
    deleteDocuments(new Term(userField, userId.toString), doCommit = false)
    update {
      Await.result(shoeboxClient.getCollectionsByUser(userId), 180 seconds).filter(_.seq <= sequenceNumber)
    }
  }

  private def update(collectionsChanged: => Seq[Collection]): Int = {
    log.info("updating Collection")
    try {
      val cnt = successCount
      indexDocuments(collectionsChanged.iterator.map(buildIndexable), commitBatchSize)
      successCount - cnt
    } catch { case e: Throwable =>
      log.error("error in Collection update", e)
      throw e
    }
  }

  def buildIndexable(collection: Collection): CollectionIndexable = {
    val bookmarks = if (collection.state == CollectionStates.ACTIVE) {
      Await.result(shoeboxClient.getBookmarksInCollection(collection.id.get), 180 seconds)
    } else {
      Seq.empty[Bookmark]
    }

    new CollectionIndexable(
      id = collection.id.get,
      sequenceNumber = collection.seq,
      isDeleted = bookmarks.isEmpty,
      collection = collection,
      bookmarks = bookmarks)
  }

  class CollectionIndexable(
    override val id: Id[Collection],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val collection: Collection,
    val bookmarks: Seq[Bookmark]
  ) extends Indexable[Collection] {

    override def buildDocument = {
      val doc = super.buildDocument

      val collListBytes = URIList.toByteArray(bookmarks)
      val collListField = buildURIListField(uriListField, collListBytes)
      val collList = URIList(collListBytes)
      doc.add(collListField)

      val uri = buildURIIdField(collList)
      doc.add(uri)

      val user = buildKeywordField(userField, collection.userId.id.toString)
      doc.add(user)

      val externalId = buildBinaryDocValuesField(externalIdField, collection.externalId.id)
      doc.add(externalId)

      doc
    }

    private def buildURIListField(field: String, uriListBytes: Array[Byte]) = {
      new BinaryDocValuesField(field, new BytesRef(uriListBytes))
    }

    private def buildURIIdField(uriList: URIList) = {
      buildIteratorField(uriField, uriList.ids.iterator){ uriId => uriId.toString }
    }
  }
}

