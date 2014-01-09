package com.keepit.search.graph

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.model.{NormalizedURI, User, Collection}
import com.keepit.search.graph.CollectionFields._
import com.keepit.search.Searcher
import com.keepit.search.line.LineIndexReader
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.BytesRef
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

  def getExternalId(id: Id[Collection]): ExternalId[Collection] = getExternalId(id.id)

  def getExternalId(id: Long): ExternalId[Collection] = {
    ExternalId[Collection](searcher.getDecodedDocValue[String](externalIdField, id)(fromByteArray).get)
  }

  def getName(id: Id[Collection]): String = getName(id.id)

  def getName(id: Long): String = {
    searcher.getDecodedDocValue[String](nameField, id)(fromByteArray).getOrElse("")
  }

  def getCollections(userId: Id[User]): Seq[(Id[Collection], String)] = {
    getUserToCollectionEdgeSet(userId).destIdSet.iterator.map{ id => (id, getName(id)) }.toSeq
  }
}

class CollectionSearcherWithUser(collectionIndexSearcher: Searcher, collectionNameIndexSearcher: Searcher, userId: Id[User]) extends CollectionSearcher(collectionIndexSearcher) {
  lazy val myCollectionEdgeSet: UserToCollectionEdgeSet = getUserToCollectionEdgeSet(userId)

  def detectCollectionNames(stems: IndexedSeq[Term]): Set[(Int, Int, Long)] = {
    collectionNameIndexSearcher.findDocIdAndAtomicReaderContext(userId.id) match {
      case Some((doc, context)) =>
        val reader = context.reader
        val collectionIdList: CollectionIdList = {
          val binaryDocValues = reader.getBinaryDocValues(CollectionNameFields.collectionIdListField)
          if (binaryDocValues != null) {
            val bytesRef = new BytesRef
            binaryDocValues.get(doc, bytesRef)
            CollectionIdList(bytesRef.bytes, bytesRef.offset, bytesRef.length)
          } else {
            null
          }
        }
        if (collectionIdList != null && collectionIdList.size > 0) {
          detectCollectionNames(stems, CollectionNameFields.stemmedNameField, doc, collectionIdList.ids, reader)
        } else {
          Set.empty[(Int, Int, Long)]
        }
      case _ =>
        Set.empty[(Int, Int, Long)]
    }
  }

  private[this] def detectCollectionNames(stems: IndexedSeq[Term], field: String, doc: Int, collectionIdList: Array[Long], reader: AtomicReader): Set[(Int, Int, Long)] = {
    val terms = stems.map{ t => new Term(field, t.text()) }
    val r = LineIndexReader(reader, doc, terms.toSet, collectionIdList.length, None)
    val detector = new CollectionNameDetector(r, collectionIdList)
    detector.detectAll(terms)
  }
}

abstract class CollectionToUriEdgeSet(override val sourceId: Id[Collection]) extends EdgeSet[Collection, NormalizedURI]

object CollectionToUriEdgeSet {
  def apply(sourceId: Id[Collection], uriList: URIList): CollectionToUriEdgeSet = {
    val set = LongArraySet.fromSorted(uriList.ids)

    new CollectionToUriEdgeSet(sourceId) with LongSetEdgeSetWithAttributes[Collection, NormalizedURI] {
      override protected val longArraySet = set
      override protected def createdAtByIndex(idx:Int): Long = {
        val datetime = uriList.createdAt(idx)
        Util.unitToMillis(datetime)
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
