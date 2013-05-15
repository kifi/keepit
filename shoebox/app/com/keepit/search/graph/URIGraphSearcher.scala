package com.keepit.search.graph

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.{NormalizedURI, User}
import com.keepit.search.graph.URIGraphFields._
import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.index.CachingIndexReader
import com.keepit.search.index.IdMapper
import com.keepit.search.index.Searcher
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.line.LineIndexReader
import com.keepit.search.query.QueryUtil
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.LongToLongArrayMap
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.util.BytesRef
import scala.collection.mutable.ArrayBuffer

class URIGraphSearcher(searcher: Searcher, myUserId: Option[Id[User]]) extends Logging {

  case class UserInfo(id: Id[User], docId: Int, publicList: URIList, privateList: URIList)

  private[this] val reader: WrappedSubReader = searcher.indexReader.asAtomicReader

  private[this] lazy val myInfo: Option[UserInfo] = {
    myUserId.map{ id =>
      val docid = reader.getIdMapper.getDocId(id.id)
      UserInfo(id, docid, getURIList(publicListField, docid), getURIList(privateListField, docid))
    }
  }

  lazy val myUriEdgeSetOpt: Option[UserToUriEdgeSetWithCreatedAt] = {
    myInfo.map{ u =>
      val uriIdMap = LongToLongArrayMap.from(concat(u.publicList.ids, u.privateList.ids),
                                             concat(u.publicList.createdAt, u.privateList.createdAt))
      UserToUriEdgeSetWithCreatedAt(u.id, uriIdMap)
    }
  }

  lazy val myPublicUriEdgeSetOpt: Option[UserToUriEdgeSetWithCreatedAt] = {
    myInfo.map{ u =>
      val uriIdMap = LongToLongArrayMap.fromSorted(u.publicList.ids, u.publicList.createdAt)
      UserToUriEdgeSetWithCreatedAt(u.id, uriIdMap)
    }
  }

  def myUriEdgeSet: UserToUriEdgeSetWithCreatedAt = {
    myUriEdgeSetOpt.getOrElse{ throw new Exception("search user was not set") }
  }

  def myPublicUriEdgeSet: UserToUriEdgeSetWithCreatedAt = {
    myPublicUriEdgeSetOpt.getOrElse{ throw new Exception("search user was not set") }
  }

  def getUserToUserEdgeSet(sourceId: Id[User], destIdSet: Set[Id[User]]) = UserToUserEdgeSet(sourceId, destIdSet)

  def getUriToUserEdgeSet(sourceId: Id[NormalizedURI]) = UriToUserEdgeSet(sourceId, searcher)

  def getUserToUriEdgeSet(sourceId: Id[User], publicOnly: Boolean = true): UserToUriEdgeSet = {
    val sourceDocId = reader.getIdMapper.getDocId(sourceId.id)
    val publicList = getURIList(publicListField, sourceDocId)
    val privateList = if (publicOnly) None else Some(getURIList(privateListField, sourceDocId))
    val uriIdSet = privateList match {
      case Some(privateList) =>
        if (publicList.size > 0) {
          LongArraySet.from(concat(publicList.ids, privateList.ids))
        } else {
          LongArraySet.fromSorted(privateList.ids)
        }
      case None => LongArraySet.fromSorted(publicList.ids)
    }
    UserToUriEdgeSet(sourceId, uriIdSet)
  }

  def getUserToUriEdgeSetWithCreatedAt(sourceId: Id[User], publicOnly: Boolean = true): UserToUriEdgeSetWithCreatedAt = {
    val sourceDocId = reader.getIdMapper.getDocId(sourceId.id)
    val publicList = getURIList(publicListField, sourceDocId)
    val privateList = if (publicOnly) None else Some(getURIList(privateListField, sourceDocId))
    val uriIdMap = privateList match {
      case Some(privateList) =>
        if (publicList.size > 0) {
          LongToLongArrayMap.from(concat(publicList.ids, privateList.ids),
                                  concat(publicList.createdAt, privateList.createdAt))
        } else {
          LongToLongArrayMap.fromSorted(privateList.ids, privateList.createdAt)
        }
      case None =>
        LongToLongArrayMap.fromSorted(publicList.ids, publicList.createdAt)
    }
    UserToUriEdgeSetWithCreatedAt(sourceId, uriIdMap)
  }

  def intersect(friends: UserToUserEdgeSet, bookmarkUsers: UriToUserEdgeSet): UserToUserEdgeSet = {
    val intersection = new ArrayBuffer[Int]
    val iter = intersect(friends.getDestDocIdSetIterator(searcher), bookmarkUsers.getDestDocIdSetIterator(searcher))

    while (iter.nextDoc != NO_MORE_DOCS) intersection += iter.docID
    UserToUserEdgeSet(friends.sourceId, searcher, intersection.toArray)
  }

  def intersect(i: DocIdSetIterator, j: DocIdSetIterator): DocIdSetIterator = {
    new DocIdSetIterator() {
      var curDoc = i.docID()
      def docID() = curDoc
      def nextDoc() = {
        var di = i.nextDoc()
        var dj = j.nextDoc()
        while (di != dj) {
          if (di < dj) di = i.advance(dj)
          else dj = j.advance(di)
        }
        curDoc = i.docID()
        curDoc
      }
      def advance(target: Int) = {
        var di = i.advance(target)
        var dj = j.advance(target)
        while (di != dj) {
          if (di < dj) di = i.advance(dj)
          else dj = j.advance(di)
        }
        curDoc = i.docID()
        curDoc
      }
    }
  }

  def intersectAny(friends: UserToUserEdgeSet, bookmarkUsers: UriToUserEdgeSet): Boolean = {
    intersectAny(friends.getDestDocIdSetIterator(searcher), bookmarkUsers.getDestDocIdSetIterator(searcher))
  }

  def intersectAny(i: DocIdSetIterator, j: DocIdSetIterator): Boolean = {
    // Note: This implementation is only more efficient than intersect(i, j).nextDoc() != NO_MORE_DOCS when the
    // intersection is empty. This code returns as soon as either iterator is exhausted instead of when both are.
    var di = i.nextDoc()
    var dj = j.nextDoc()
    while (di != dj) {
      if (di < dj) {
        di = i.advance(dj)
        if (di == NO_MORE_DOCS) return false
      } else {
        dj = j.advance(di)
        if (dj == NO_MORE_DOCS) return false
      }
    }
    di != NO_MORE_DOCS
  }

  private def getURIList(field: String, userDocId: Int): URIList = {
    if (userDocId >= 0) {
      var docValues = reader.getBinaryDocValues(field)
      if (docValues != null) {
        var ref = new BytesRef()
        docValues.get(userDocId, ref)
        if (ref.length > 0) {
          return URIList(ref.bytes, ref.offset, ref.length)
        }
      } else {
        // backward compatibility
        var docValues = reader.getBinaryDocValues(userField)
        if (docValues != null) {
          var ref = new BytesRef()
          docValues.get(userDocId, ref)
          if (ref.length > 0) {
            val old = URIList(ref.bytes, ref.offset, ref.length).asInstanceOf[URIListOld]
            if (field == publicListField) return old
            else return old.getPrivateURIList
          }
        }
      }
    }
    URIList.empty
  }

  def openPersonalIndex(query: Query): Option[(CachingIndexReader, IdMapper)] = {
    val terms = QueryUtil.getTerms(query)
    myInfo.map{ u =>
      val numDocs = myInfo.get.publicList.size + myInfo.get.privateList.size
      val ids = concat(u.publicList.ids, u.privateList.ids)

      if (numDocs != ids.length) log.error(s"numDocs=$numDocs ids.length${ids.length} publicList.size=${u.publicList.size} privateList.size=${u.privateList.size}")

      (LineIndexReader(reader, u.docId, terms, numDocs), new ArrayIdMapper(ids))
    }
  }

  private def concat(a: Array[Long], b: Array[Long]): Array[Long] = {
    val ret = new Array[Long](a.length + b.length)
    System.arraycopy(a, 0, ret, 0, a.length)
    System.arraycopy(b, 0, ret, a.length, b.length)
    ret
  }
}

abstract class UserToUserEdgeSet(override val sourceId: Id[User]) extends EdgeSet[User, User]

object UserToUserEdgeSet{
  def apply(sourceId: Id[User], destIds: Set[Id[User]]): UserToUserEdgeSet = {
    new UserToUserEdgeSet(sourceId) with IdSetEdgeSet[User, User] {
      override def destIdSet: Set[Id[User]] = destIds
    }
  }

  def apply(sourceId: Id[User], currentSearcher: Searcher, destIds: Array[Int]): UserToUserEdgeSet = {
    new UserToUserEdgeSet(sourceId) with DocIdSetEdgeSet[User, User] {
      override val docids: Array[Int] = destIds
      override val searcher: Searcher = currentSearcher
    }
  }
}

abstract class UserToUriEdgeSet(override val sourceId: Id[User]) extends EdgeSet[User, NormalizedURI]

object UserToUriEdgeSet {
  def apply(sourceId: Id[User], destIds: Set[Long]): UserToUriEdgeSet = {
    new UserToUriEdgeSet(sourceId) with LongSetEdgeSet[User, NormalizedURI] {
      override def destIdLongSet: Set[Long] = destIds
    }
  }
}

abstract class UserToUriEdgeSetWithCreatedAt(override val sourceId: Id[User]) extends EdgeSet[User, NormalizedURI] with CreatedAt[NormalizedURI]

object UserToUriEdgeSetWithCreatedAt {
  def apply(sourceId: Id[User], map: Map[Long, Long]): UserToUriEdgeSetWithCreatedAt = {
    new UserToUriEdgeSetWithCreatedAt(sourceId) with LongToLongMapEdgeSetWithCreatedAt[User, NormalizedURI] {
      override val destIdMap: Map[Long, Long] = map
    }
  }
}

abstract class UriToUserEdgeSet(override val sourceId: Id[NormalizedURI]) extends EdgeSet[NormalizedURI, User]

object UriToUserEdgeSet {
  def apply(sourceId: Id[NormalizedURI], currentSearcher: Searcher): UriToUserEdgeSet = {
    new UriToUserEdgeSet(sourceId) with LuceneBackedEdgeSet[NormalizedURI, User] {
      override val searcher: Searcher = currentSearcher
      override def createSourceTerm = new Term(uriField, sourceId.toString)
    }
  }
}
