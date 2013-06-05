package com.keepit.search.graph

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.{NormalizedURI, User, Collection}
import com.keepit.search.graph.CollectionFields._
import com.keepit.search.index.Searcher
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.collection.mutable.ArrayBuffer


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
}

abstract class CollectionToUriEdgeSet(override val sourceId: Id[Collection]) extends EdgeSet[Collection, NormalizedURI]

object CollectionToUriEdgeSet {
  def apply(sourceId: Id[Collection], uriList: URIList): CollectionToUriEdgeSet = {
    val set = LongArraySet.fromSorted(uriList.ids)

    new CollectionToUriEdgeSet(sourceId) with LongSetEdgeSetWithCreatedAt[Collection, NormalizedURI] {
      override protected val longArraySet = set
      override protected def createdAtByIndex(idx:Int): Long = {
        val datetime = uriList.createdAt(idx)
        URIList.unitToMillis(datetime)
      }
      override protected def isPublicByIndex(idx: Int): Boolean = false
    }
  }
}

abstract class UserToCollectionEdgeSet(override val sourceId: Id[User]) extends EdgeSet[User, Collection]

object UserToCollectionEdgeSet {
  def apply(sourceId: Id[User], currentSearcher: Searcher): UserToCollectionEdgeSet = {
    new UserToCollectionEdgeSet(sourceId) with LuceneBackedEdgeSet[User, Collection] {
      override val searcher: Searcher = currentSearcher
      override def createSourceTerm = new Term(userField, sourceId.toString)
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
      override def createSourceTerm = new Term(uriField, sourceId.toString)
    }
  }
}
