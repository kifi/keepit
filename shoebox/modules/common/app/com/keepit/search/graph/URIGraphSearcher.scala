package com.keepit.search.graph

import com.keepit.common.akka.MonitoredAwait
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
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._


class URIGraphSearcher(searcher: Searcher, storeSearcher: Searcher, myUserId: Option[Id[User]], shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait) extends BaseGraphSearcher(searcher) with Logging {

  private[this] val friendIdsFutureOpt = myUserId.map{ shoeboxClient.getConnectedUsers(_) }

  private[this] lazy val myInfo: Option[UserInfo] = {
    myUserId.map{ id =>
      val docid = reader.getIdMapper.getDocId(id.id)
      val publicList = getURIList(publicListField, docid)
      val privateList = getURIList(privateListField, docid)
      val bookmarkIdArray = getLongArray(bookmarkIdField, docid)
      new UserInfo(id, docid, publicList, privateList, bookmarkIdArray)
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

  private[this] lazy val friendEdgeSetOpt = friendIdsFutureOpt.map{ future =>
    val friendIds = monitoredAwait.result(future, 5 seconds)
    UserToUserEdgeSet(myUserId.get, friendIds)
  }
  private[this] val friendsUriEdgeSetsOpt = friendEdgeSetOpt.map{ friendEdgeSet =>
    friendEdgeSet.destIdSet.foldLeft(Map.empty[Long, UserToUriEdgeSet]){ (m, f) =>
      m + (f.id -> getUserToUriEdgeSet(f, publicOnly = true))
    }
  }

  def friendEdgeSet: UserToUserEdgeSet = {
    friendEdgeSetOpt.getOrElse{ throw new Exception("search user was not set") }
  }
  def friendsUriEdgeSets: Map[Long, UserToUriEdgeSet] = {
    friendsUriEdgeSetsOpt.getOrElse{ throw new Exception("search user was not set") }
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
          UserToUriEdgeSet(sourceId, privateList, false)
        }
      case None => UserToUriEdgeSet(sourceId, publicList, true)
    }
  }

  def intersect(friends: UserToUserEdgeSet, bookmarkUsers: UriToUserEdgeSet): UserToUserEdgeSet = {
    val intersection = new ArrayBuffer[Int]
    val iter = intersect(friends.getDestDocIdSetIterator(searcher), bookmarkUsers.getDestDocIdSetIterator(searcher))

    while (iter.nextDoc != NO_MORE_DOCS) intersection += iter.docID
    UserToUserEdgeSet(friends.sourceId, searcher, intersection.toArray)
  }

  def intersectAny(friends: UserToUserEdgeSet, bookmarkUsers: UriToUserEdgeSet): Boolean = {
    intersectAny(friends.getDestDocIdSetIterator(searcher), bookmarkUsers.getDestDocIdSetIterator(searcher))
  }


  def openPersonalIndex(query: Query): Option[(CachingIndexReader, IdMapper)] = {
    val terms = QueryUtil.getTerms(query)
    myInfo.map{ u =>
      if (u.mapper.maxDoc != u.uriIdArray.length)
        log.error(s"mapper.maxDocs=${u.mapper.maxDoc} ids.length=${u.uriIdArray.length} publicList.size=${u.publicList.size} privateList.size=${u.privateList.size}")

      (LineIndexReader(reader, u.docId, terms, u.uriIdArray.length), u.mapper)
    }
  }

  def getBookmarkRecord(uriId: Id[NormalizedURI]): Option[BookmarkRecord] = {
    import com.keepit.search.graph.BookmarkRecordSerializer._

    val bookmarkId = myUriEdgeSet.accessor.getBookmarkId(uriId.id)
    storeSearcher.getDecodedDocValue[BookmarkRecord](BookmarkStoreFields.recField, bookmarkId)
  }
}

class UserInfo(val id: Id[User], val docId: Int, val publicList: URIList, val privateList: URIList, val bookmarkIdArray: Array[Long]) {
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

abstract class UserToUriEdgeSet(override val sourceId: Id[User]) extends EdgeSet[User, NormalizedURI]

object UserToUriEdgeSet {
  def apply(sourceId: Id[User], uriList: URIList, isPublicEdgeSet: Boolean): UserToUriEdgeSet = {
    val set = LongArraySet.fromSorted(uriList.ids)

    new UserToUriEdgeSet(sourceId) with LongSetEdgeSetWithAttributes[User, NormalizedURI] {
      override protected val longArraySet = set
      override protected def createdAtByIndex(idx:Int): Long = {
        val datetime = uriList.createdAt(idx)
        URIList.unitToMillis(datetime)
      }
      override protected def isPublicByIndex(idx: Int): Boolean = isPublicEdgeSet
    }
  }

  def apply(sourceId: Id[User], publicList: URIList, privateList: URIList): UserToUriEdgeSet = {
    val publicIds = publicList.ids
    val privateIds = privateList.ids

    if (publicIds.length == 0) apply(sourceId, privateList, false)
    else if (privateIds.length == 0) apply(sourceId, publicList, true)
    else {
      val set = LongArraySet.from(concat(publicIds, privateIds))
      val pubListSize = publicIds.length

      new UserToUriEdgeSet(sourceId) with LongSetEdgeSetWithAttributes[User, NormalizedURI] {
        override protected val longArraySet = set
        override protected def createdAtByIndex(idx:Int): Long = {
          val datetime = if (idx < pubListSize) publicList.createdAt(idx) else privateList.createdAt(idx - pubListSize)
          URIList.unitToMillis(datetime)
        }
        override protected def isPublicByIndex(idx: Int): Boolean = (idx < pubListSize)
      }
    }
  }

  def apply(myInfo: UserInfo): UserToUriEdgeSet = {
    val sourceId: Id[User] = myInfo.id
    val publicList = myInfo.publicList
    val privateList = myInfo.privateList
    val bookmarkIds = myInfo.bookmarkIdArray
    val set = LongArraySet.from(myInfo.uriIdArray, myInfo.mapper.reserveMapper)

    val pubListSize = publicList.size
    new UserToUriEdgeSet(sourceId) with LongSetEdgeSetWithAttributes[User, NormalizedURI] {
      override protected val longArraySet = set
      override protected def createdAtByIndex(idx:Int): Long = {
        val datetime = if (idx < pubListSize) publicList.createdAt(idx) else privateList.createdAt(idx - pubListSize)
        URIList.unitToMillis(datetime)
      }
      override protected def isPublicByIndex(idx: Int): Boolean = (idx < pubListSize)
      override protected def bookmarkIdByIndex(idx: Int): Long = bookmarkIds(idx)
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
