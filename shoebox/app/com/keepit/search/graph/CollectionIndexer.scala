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

object CollectionFields {
  val userField = "coll_usr"
  val collectionListField = "coll_list"
  val collectionField = "coll"

  def decoders() = Map(
    collectionListField -> DocUtil.URIListDecoder
  )
}

class CollectionIndexer(
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    decoders: Map[String, FieldDecoder],
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    bookmarkRepo: BookmarkRepo,
    db: Database,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[Collection](indexDirectory, indexWriterConfig, decoders) {

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
      db.readOnly { implicit s =>
        collectionRepo.getCollectionsChanged(sequenceNumber)
      }
    }
  }

  def update(userId: Id[User]): Int = {
    deleteDocuments(new Term(CollectionFields.userField, userId.toString), doCommit = false)
    update {
      db.readOnly { implicit s =>
        collectionRepo.getByUser(userId).map{ collection => (collection.id.get, userId, SequenceNumber.MinValue) }
      }
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
    val bookmarks = db.readOnly { implicit session =>
      keepToCollectionRepo.getBookmarksInCollection(collectionId).map{ bookmarkRepo.get(_) }
    }
    new CollectionListIndexable(id = collectionId,
                                sequenceNumber = seq,
                                isDeleted = bookmarks.isEmpty,
                                userId = userId,
                                bookmarks = bookmarks)
  }

  class CollectionListIndexable(
    override val id: Id[Collection],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val userId: Id[User],
    val bookmarks: Seq[Bookmark]
  ) extends Indexable[Collection] {

    override def buildDocument = {
      val doc = super.buildDocument
      val collListBytes = URIList.toByteArray(bookmarks)
      val collListField = buildURIListField(CollectionFields.collectionListField, collListBytes)
      val collList = URIList(collListBytes)

      doc.add(collListField)

      val uri = buildURIIdField(collList)
      doc.add(uri)

      val user = buildKeywordField(URIGraphFields.userField, userId.toString)
      doc.add(user)

      doc
    }

    private def buildURIListField(field: String, uriListBytes: Array[Byte]) = {
      new BinaryDocValuesField(field, new BytesRef(uriListBytes))
    }

    private def buildURIIdField(uriList: URIList) = {
      buildIteratorField(URIGraphFields.uriField, uriList.ids.iterator){ uriId => uriId.toString }
    }
  }
}

