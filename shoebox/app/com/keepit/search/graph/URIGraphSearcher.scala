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

  private[this] val reader: WrappedSubReader = searcher.indexReader.asAtomicReader

  private[this] lazy val myInfo: Option[UserInfo] = {
    myUserId.map{ id =>
      val docid = reader.getIdMapper.getDocId(id.id)
      val publicList = getURIList(publicListField, docid)
      val privateList = getURIList(privateListField, docid)
      new UserInfo(id, docid, publicList, privateList)
    }
  }

  lazy val myUriEdgeSetOpt: Option[UserToUriEdgeSet] = myInfo.map(UserToUriEdgeSet(_))
  lazy val myPublicUriEdgeSetOpt: Option[UserToUriEdgeSet] = myInfo.map(UserToUriEdgeSet(_))

  def myUriEdgeSet: UserToUriEdgeSet = {
    myUriEdgeSetOpt.getOrElse{ throw new Exception("search user was not set") }
  }

  def myPublicUriEdgeSet: UserToUriEdgeSet = {
    myPublicUriEdgeSetOpt.getOrElse{ throw new Exception("search user was not set") }
  }

  def getUserToUserEdgeSet(sourceId: Id[User], destIdSet: Set[Id[User]]) = UserToUserEdgeSet(sourceId, destIdSet)

  def getUriToUserEdgeSet(sourceId: Id[NormalizedURI]) = UriToUserEdgeSet(sourceId, searcher)

  def getUserToUriEdgeSet(sourceId: Id[User], publicOnly: Boolean = true): UserToUriEdgeSet = {
    val sourceDocId = reader.getIdMapper.getDocId(sourceId.id)
    val publicList = getURIList(publicListField, sourceDocId)
    val privateList = if (publicOnly) None else Some(getURIList(privateListField, sourceDocId))
    privateList match {
      case Some(privateList) =>
        if (publicList.size > 0) {
          UserToUriEdgeSet(sourceId, publicList, privateList)
        } else {
          UserToUriEdgeSet(sourceId, privateList)
        }
      case None => UserToUriEdgeSet(sourceId, publicList)
    }
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
      }
    }
    URIList.empty
  }

  def openPersonalIndex(query: Query): Option[(CachingIndexReader, IdMapper)] = {
    val terms = QueryUtil.getTerms(query)
    myInfo.map{ u =>
      if (u.mapper.maxDoc != u.uriIdArray.length)
        log.error(s"mapper.maxDocs=${u.mapper.maxDoc} ids.length=${u.uriIdArray.length} publicList.size=${u.publicList.size} privateList.size=${u.privateList.size}")

      (LineIndexReader(reader, u.docId, terms, u.uriIdArray.length), u.mapper)
    }
  }
}

class UserInfo(val id: Id[User], val docId: Int, val publicList: URIList, val privateList: URIList) {
  val uriIdArray: Array[Long] = {
    val publicIds = publicList.ids
    val privateIds = privateList.ids

    if (publicIds.length == 0) privateIds
    else if (privateIds.length == 0) publicIds
    else concat(publicIds, privateIds)
  }

  val mapper: ArrayIdMapper = new ArrayIdMapper(uriIdArray)

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

abstract class UserToUriEdgeSet(override val sourceId: Id[User]) extends EdgeSet[User, NormalizedURI] with CreatedAt[NormalizedURI]

object UserToUriEdgeSet {
  def apply(sourceId: Id[User], uriList: URIList): UserToUriEdgeSet = {
    val set = LongArraySet.fromSorted(uriList.ids)

    new UserToUriEdgeSet(sourceId) with LongSetEdgeSetWithCreatedAt[User, NormalizedURI] {
      override protected val longArraySet = set
      override protected def createdAt(idx:Int): Long = {
        URIList.unitToMillis(uriList.createdAt(idx))
      }
    }
  }

  def apply(sourceId: Id[User], publicList: URIList, privateList: URIList): UserToUriEdgeSet = {
    val publicIds = publicList.ids
    val privateIds = privateList.ids

    if (publicIds.length == 0) apply(sourceId, privateList)
    else if (privateIds.length == 0) apply(sourceId, publicList)
    else {
      val set = LongArraySet.from(concat(publicIds, privateIds))
      val pubListSize = publicIds.length

      new UserToUriEdgeSet(sourceId) with LongSetEdgeSetWithCreatedAt[User, NormalizedURI] {
        override protected val longArraySet = set
        override protected def createdAt(idx:Int): Long = {
          val datetime = if (idx < pubListSize) publicList.createdAt(idx) else privateList.createdAt(idx - pubListSize)
          URIList.unitToMillis(datetime)
        }
      }
    }
  }

  def apply(myInfo: UserInfo): UserToUriEdgeSet = {
    val sourceId: Id[User] = myInfo.id
    val publicList = myInfo.publicList
    val privateList = myInfo.privateList
    val set = LongArraySet.from(myInfo.uriIdArray, myInfo.mapper.reserveMapper)

    val pubListSize = publicList.size
    new UserToUriEdgeSet(sourceId) with LongSetEdgeSetWithCreatedAt[User, NormalizedURI] {
      override protected val longArraySet = set
      override protected def createdAt(idx:Int) = {
        val datetime = if (idx < pubListSize) publicList.createdAt(idx) else privateList.createdAt(idx - pubListSize)
        URIList.unitToMillis(datetime)
      }
    }
  }

  private def concat(a: Array[Long], b: Array[Long]): Array[Long] = {
    val ret = new Array[Long](a.length + b.length)
    System.arraycopy(a, 0, ret, 0, a.length)
    System.arraycopy(b, 0, ret, a.length, b.length)
    ret
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
