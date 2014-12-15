package com.keepit.search.index.graph.collection

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.model.{ Hashtag, NormalizedURI, User, Collection }
import com.keepit.search.index.Searcher
import com.keepit.search.util.LongArraySet
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.index.graph.BaseGraphSearcher
import com.keepit.search.index.graph.DocIdSetEdgeSet
import com.keepit.search.index.graph.EdgeSet
import com.keepit.search.index.graph.LongArraySetEdgeSet
import com.keepit.search.index.graph.LuceneBackedEdgeSet
import com.keepit.search.index.graph.URIList
import com.keepit.search.index.graph.Util
import com.keepit.search.index.graph.collection.CollectionFields._
import com.keepit.search.index.graph.LuceneBackedBookmarkInfoAccessor

object CollectionSearcher {
  def apply(collectionIndexer: CollectionIndexer): CollectionSearcher = new CollectionSearcher(collectionIndexer.getSearcher)
  def apply(userId: Id[User], collectionIndexer: CollectionIndexer): CollectionSearcherWithUser = {
    val collectionIndexSearcher = collectionIndexer.getSearcher
    new CollectionSearcherWithUser(collectionIndexSearcher, userId)
  }
}

class CollectionSearcher(searcher: Searcher) extends BaseGraphSearcher(searcher) with Logging {

  def getUserToCollectionEdgeSet(sourceId: Id[User]) = UserToCollectionEdgeSet(sourceId, searcher)

  def getUriToCollectionEdgeSet(sourceId: Id[NormalizedURI]) = UriToCollectionEdgeSet(sourceId, searcher)

  def getCollectionToUriEdgeSet(sourceId: Id[Collection]): CollectionToUriEdgeSet = {
    val sourceDocId = reader.getIdMapper.getDocId(sourceId.id)
    val uriList = getURIList(uriListField, sourceDocId)
    CollectionToUriEdgeSet(sourceId, uriList)
  }

  def intersect(userToCollection: UserToCollectionEdgeSet, uriToCollection: UriToCollectionEdgeSet): UserToCollectionEdgeSet = {
    val intersection = new ArrayBuffer[Int]
    val iter = intersect(userToCollection.getDestDocIdSetIterator(searcher), uriToCollection.getDestDocIdSetIterator(searcher))

    while (iter.nextDoc != NO_MORE_DOCS) intersection += iter.docID
    UserToCollectionEdgeSet(userToCollection.sourceId, searcher, intersection.toArray)
  }

  def getExternalId(id: Id[Collection]): Option[ExternalId[Collection]] = getExternalId(id.id)

  def getExternalId(id: Long): Option[ExternalId[Collection]] = {
    searcher.getDecodedDocValue[String](externalIdField, id)(fromByteArray).map { ExternalId[Collection](_) }
  }
}

class CollectionSearcherWithUser(collectionIndexSearcher: Searcher, userId: Id[User]) extends CollectionSearcher(collectionIndexSearcher) {
  lazy val myCollectionEdgeSet: UserToCollectionEdgeSet = getUserToCollectionEdgeSet(userId)

  private[this] var collectionIdCache: Map[Long, Option[ExternalId[Collection]]] = Map()

  override def getExternalId(id: Long): Option[ExternalId[Collection]] = {
    collectionIdCache.getOrElse(id, {
      val extId = super.getExternalId(id)
      collectionIdCache += (id -> extId)
      extId
    })
  }
}

abstract class CollectionToUriEdgeSet(override val sourceId: Id[Collection]) extends EdgeSet[Collection, NormalizedURI]

object CollectionToUriEdgeSet {
  def apply(sourceId: Id[Collection], uriList: URIList): CollectionToUriEdgeSet = {
    val set = LongArraySet.fromSorted(uriList.ids)

    new CollectionToUriEdgeSet(sourceId) with LongArraySetEdgeSet[Collection, NormalizedURI] {
      override protected val longArraySet = set

      override def accessor = new LuceneBackedBookmarkInfoAccessor(this, longArraySet) {
        override protected def createdAtByIndex(idx: Int): Long = {
          val datetime = uriList.createdAt(idx)
          Util.unitToMillis(datetime)
        }
        protected def bookmarkIdByIndex(idx: Int): Long = throw new UnsupportedOperationException
        protected def isPublicByIndex(idx: Int): Boolean = throw new UnsupportedOperationException
      }

    }
  }
}

abstract class UserToCollectionEdgeSet(override val sourceId: Id[User]) extends EdgeSet[User, Collection]

object UserToCollectionEdgeSet {
  def apply(sourceId: Id[User], currentSearcher: Searcher): UserToCollectionEdgeSet = {
    new UserToCollectionEdgeSet(sourceId) with LuceneBackedEdgeSet[User, Collection] {
      override val searcher: Searcher = currentSearcher
      override val sourceFieldName = userField
    }
  }

  def apply(sourceId: Id[User], currentSearcher: Searcher, destIds: Array[Int]): UserToCollectionEdgeSet = {
    new UserToCollectionEdgeSet(sourceId) with DocIdSetEdgeSet[User, Collection] {
      override val docids: Array[Int] = destIds
      override val searcher: Searcher = currentSearcher
    }
  }

}

abstract class UriToCollectionEdgeSet(override val sourceId: Id[NormalizedURI]) extends EdgeSet[NormalizedURI, Collection]

object UriToCollectionEdgeSet {
  def apply(sourceId: Id[NormalizedURI], currentSearcher: Searcher): UriToCollectionEdgeSet = {
    new UriToCollectionEdgeSet(sourceId) with LuceneBackedEdgeSet[NormalizedURI, Collection] {
      override val searcher: Searcher = currentSearcher
      override val sourceFieldName = uriField
    }
  }
}
