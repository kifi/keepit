package com.keepit.search.index.graph.collection

import com.keepit.common.db._
import com.keepit.common.healthcheck.{ AirbrakeNotifier }
import com.keepit.common.strings._
import com.keepit.model._
import com.keepit.model.CollectionStates._
import com.keepit.search.index._
import com.keepit.search.index.DocUtil
import com.keepit.search.index.{ Indexable, Indexer }
import com.keepit.search.index.graph.URIList
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }
import com.keepit.search.index.IndexInfo
import com.keepit.search.index.sharding.Shard

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

class CollectionIndexer(
    indexDirectory: IndexDirectory,
    override val airbrake: AirbrakeNotifier) extends Indexer[Collection, Collection, CollectionIndexer](indexDirectory) {

  import CollectionIndexer.CollectionIndexable

  override val commitBatchSize = 100
  private val fetchSize = 100

  override def onFailure(indexable: Indexable[Collection, Collection], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = throw new UnsupportedOperationException()

  def update(name: String, data: Seq[(Collection, Seq[KeepUriAndTime])], shard: Shard[NormalizedURI]): Int = updateLock.synchronized {
    val cnt = doUpdate("CollectionIndex" + name) {
      data.iterator.map {
        case (collection, bookmarks) =>
          buildIndexable(collection, bookmarks.filter(b => shard.contains(b.uriId)))
      }
    }
    cnt
  }

  def buildIndexable(collection: Collection, bookmarks: Seq[KeepUriAndTime]): CollectionIndexable = {
    new CollectionIndexable(
      id = collection.id.get,
      sequenceNumber = collection.seq,
      isDeleted = bookmarks.isEmpty,
      collection = collection,
      normalizedUris = bookmarks)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos("CollectionIndex" + name)
  }
}

object CollectionIndexer {
  import CollectionFields._

  def shouldDelete(collection: Collection): Boolean = (collection.state == INACTIVE)

  def fetchData(sequenceNumber: SequenceNumber[Collection], fetchSize: Int, shoeboxClient: ShoeboxServiceClient): Seq[(Collection, Seq[KeepUriAndTime])] = {
    val collections: Seq[Collection] = Await.result(shoeboxClient.getCollectionsChanged(sequenceNumber, fetchSize), 180 seconds)
    collections.map { collection =>
      val bookmarks = if (collection.state == CollectionStates.ACTIVE) {
        Await.result(shoeboxClient.getUriIdsInCollection(collection.id.get), 180 seconds)
      } else {
        Seq.empty[KeepUriAndTime]
      }
      (collection, bookmarks)
    }
  }

  def fetchData(id: Id[Collection], userId: Id[User], shoeboxClient: ShoeboxServiceClient): (Collection, Seq[KeepUriAndTime]) = {
    val collection = Await.result(shoeboxClient.getCollectionsByUser(userId), 180 seconds).find(_.id.get == id).get
    val bookmarks = if (collection.state == CollectionStates.ACTIVE) {
      Await.result(shoeboxClient.getUriIdsInCollection(collection.id.get), 180 seconds)
    } else {
      Seq.empty[KeepUriAndTime]
    }
    (collection, bookmarks)
  }

  class CollectionIndexable(
      override val id: Id[Collection],
      override val sequenceNumber: SequenceNumber[Collection],
      override val isDeleted: Boolean,
      val collection: Collection,
      val normalizedUris: Seq[KeepUriAndTime]) extends Indexable[Collection, Collection] {

    override def buildDocument = {
      val doc = super.buildDocument

      val collListBytes = URIList.toByteArray(normalizedUris)
      val collListFields = buildURIListField(uriListField, collListBytes)
      val collList = URIList(collListBytes)
      collListFields.foreach { doc.add }

      val uri = buildURIIdField(collList)
      doc.add(uri)

      val user = buildKeywordField(userField, collection.userId.id.toString)
      doc.add(user)

      val externalId = buildBinaryDocValuesField(externalIdField, collection.externalId.id)
      doc.add(externalId)

      doc
    }

    private def buildURIListField(field: String, uriListBytes: Array[Byte]) = {
      buildExtraLongBinaryDocValuesField(field, uriListBytes)
    }

    private def buildURIIdField(uriList: URIList) = {
      buildIteratorField(uriField, uriList.ids.iterator) { uriId => uriId.toString }
    }
  }
}

