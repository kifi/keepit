package com.keepit.search.graph.bookmark

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.search.Lang
import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.index.CachedIndex
import com.keepit.search.index.CachingIndexReader
import com.keepit.search.index.IdMapper
import com.keepit.search.Searcher
import com.keepit.search.line.LineIndexReader
import com.keepit.search.query.QueryUtil
import com.keepit.search.util.LongArraySet
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.SharingUserInfo
import com.keepit.search.graph._
import com.keepit.search.graph.bookmark.URIGraphFields._
import com.keepit.search.graph.BookmarkInfoAccessor
import com.keepit.search.graph.LuceneBackedBookmarkInfoAccessor
import com.keepit.search.graph.LongArraySetEdgeSet
import com.keepit.search.graph.user.UserGraphsSearcher
import play.modules.statsd.api.Statsd

object URIGraphSearcher {
  def apply(uriGraphIndexer: URIGraphIndexer): URIGraphSearcher = {
    val (indexSearcher, storeSearcher) = uriGraphIndexer.getSearchers
    new URIGraphSearcher(indexSearcher, storeSearcher)
  }

  def apply(userId: Id[User], uriGraphIndexer: URIGraphIndexer, userGraphsSearcher: UserGraphsSearcher): URIGraphSearcherWithUser = {
    val (indexSearcher, storeSearcher) = uriGraphIndexer.getSearchers
    new URIGraphSearcherWithUser(indexSearcher, storeSearcher, userId, userGraphsSearcher)
  }
}

class URIGraphSearcher(searcher: Searcher, storeSearcher: Searcher) extends BaseGraphSearcher(searcher) with Logging {

  def getUserToUserEdgeSet(sourceId: Id[User], destIdSet: Set[Id[User]]) = UserToUserEdgeSet.fromIdSet(sourceId, destIdSet)

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
    UserToUserEdgeSet.fromDocIds(friends.sourceId, searcher, intersection.toArray)
  }

  def intersectAny(friends: UserToUserEdgeSet, bookmarkUsers: UriToUserEdgeSet): Boolean = {
    intersectAny(friends.getDestDocIdSetIterator(searcher), bookmarkUsers.getDestDocIdSetIterator(searcher))
  }
}

class URIGraphSearcherWithUser(searcher: Searcher, storeSearcher: Searcher, myUserId: Id[User], userGraphsSearcher: UserGraphsSearcher)
    extends URIGraphSearcher(searcher, storeSearcher) {

  if (userGraphsSearcher.userId != myUserId) throw new IllegalArgumentException("user id mismatch")

  override def getUserToUriEdgeSet(sourceId: Id[User], publicOnly: Boolean = true): UserToUriEdgeSet = {
    if (publicOnly != true && sourceId != myUserId) throw new NotAuthorizedURIGraphQueryException(s"requesting user ${myUserId} should not have access to user ${sourceId}'s private keeps")

    super.getUserToUriEdgeSet(sourceId, publicOnly)
  }

  private[this] val friendIdsFuture: Future[Set[Long]] = userGraphsSearcher.getConnectedUsersFuture()
  private[this] val unfriendedFuture: Future[Set[Long]] = userGraphsSearcher.getUnfriendedFuture()

  private[this] lazy val myInfo: UserInfo = {
    val docid = reader.getIdMapper.getDocId(myUserId.id)
    val publicList = getURIList(publicListField, docid)
    val privateList = getURIList(privateListField, docid)
    val bookmarkIdArray = getLongArray(bookmarkIdField, docid)
    new UserInfo(myUserId, publicList, privateList, bookmarkIdArray)
  }

  lazy val myUriEdgeSet: UserToUriEdgeSet = UserToUriEdgeSet(myInfo)
  lazy val myPublicUriEdgeSet: UserToUriEdgeSet = UserToUriEdgeSet(myInfo)

  lazy val friendEdgeSet = {
    val startTime = System.currentTimeMillis
    val friendIds = Await.result(friendIdsFuture, 5 seconds)
    val waitTime = System.currentTimeMillis - startTime

    Future { Statsd.timing("mainSearch.friendIdsFutureWait", waitTime) }

    UserToUserEdgeSet.fromLongSet(myUserId, friendIds)
  }

  lazy val friendsUriEdgeSets = {
    friendEdgeSet.destIdSet.foldLeft(Map.empty[Long, UserToUriEdgeSet]) { (m, f) =>
      m + (f.id -> getUserToUriEdgeSet(f, publicOnly = true))
    }
  }

  lazy val searchFriendEdgeSet = {
    val startTime = System.currentTimeMillis
    val unfriended = Await.result(unfriendedFuture, 5 seconds)
    val friendIds = Await.result(friendIdsFuture, 5 seconds)
    val waitTime = System.currentTimeMillis - startTime

    Future { Statsd.timing("mainSearch.searchFriendsFutureWait", waitTime) }

    if (unfriended.isEmpty) UserToUserEdgeSet.fromLongSet(myUserId, friendIds)
    else UserToUserEdgeSet.fromLongSet(myUserId, (friendIds -- unfriended))
  }

  lazy val searchFriendsUriEdgeSets = {
    searchFriendEdgeSet.destIdSet.foldLeft(Map.empty[Long, UserToUriEdgeSet]) { (m, f) =>
      m + (f.id -> getUserToUriEdgeSet(f, publicOnly = true))
    }
  }

  def getSharingUserInfo(uriId: Id[NormalizedURI]): SharingUserInfo = {
    val keepersEdgeSet = getUriToUserEdgeSet(uriId)
    val sharingUserIds = intersect(friendEdgeSet, keepersEdgeSet).destIdSet
    SharingUserInfo(sharingUserIds, keepersEdgeSet.size)
  }

  @volatile
  private[this] var cachedIndexOpt: Option[CachedIndex] = None

  def openPersonalIndex(query: Query): (CachingIndexReader, IdMapper) = {
    if (myInfo.mapper.maxDoc != myInfo.uriIdArray.length)
      log.error(s"mapper.maxDocs=${myInfo.mapper.maxDoc} ids.length=${myInfo.uriIdArray.length} publicList.size=${myInfo.publicList.size} privateList.size=${myInfo.privateList.size}")

    searcher.findDocIdAndAtomicReaderContext(myUserId.id) match {
      case Some((docId, context)) =>
        val terms = QueryUtil.getTerms(query)
        val r = LineIndexReader(context.reader, docId, terms, myInfo.uriIdArray.length, cachedIndexOpt)
        cachedIndexOpt = Some(r.index)
        (r, myInfo.mapper)
      case _ =>
        (LineIndexReader.empty, myInfo.mapper)
    }
  }

  def getBookmarkRecord(uriId: Id[NormalizedURI]): Option[BookmarkRecord] = {
    import com.keepit.search.graph.bookmark.BookmarkRecordSerializer._

    val bookmarkId = myUriEdgeSet.accessor.asInstanceOf[BookmarkInfoAccessor[User, NormalizedURI]].getBookmarkId(uriId.id)
    storeSearcher.getDecodedDocValue[BookmarkRecord](BookmarkStoreFields.recField, bookmarkId)
  }

  def getLangProfile(): Map[Lang, Int] = {
    val prof = searcher.getDecodedDocValue[String](URIGraphFields.langProfField, myUserId.id)((arr, offset, length) => new String(arr, offset, length))
    prof match {
      case Some(prof) =>
        prof.split(",").filter(_.indexOf(":") > 0).map { p =>
          val len = p.indexOf(":")
          (Lang(p.substring(0, len)) -> p.substring(len + 1).toInt)
        }.toMap
      case None => Map()
    }
  }
}

class NotAuthorizedURIGraphQueryException(msg: String) extends Exception(msg)

class UserInfo(val id: Id[User], val publicList: URIList, val privateList: URIList, val bookmarkIdArray: Array[Long]) {
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

object UserToUserEdgeSet {
  def fromIdSet(sourceId: Id[User], destIds: Set[Id[User]]): UserToUserEdgeSet = {
    new UserToUserEdgeSet(sourceId) with IdSetEdgeSet[User, User] {
      override def destIdSet: Set[Id[User]] = destIds
    }
  }

  def fromLongSet(sourceId: Id[User], destIds: Set[Long]): UserToUserEdgeSet = {
    new UserToUserEdgeSet(sourceId) with LongSetEdgeSet[User, User] {
      override def destIdLongSet: Set[Long] = destIds
    }
  }

  def fromDocIds(sourceId: Id[User], currentSearcher: Searcher, destIds: Array[Int]): UserToUserEdgeSet = {
    new UserToUserEdgeSet(sourceId) with DocIdSetEdgeSet[User, User] {
      override val docids: Array[Int] = destIds
      override val searcher: Searcher = currentSearcher
    }
  }
}

abstract class UserToUriEdgeSet(override val sourceId: Id[User]) extends EdgeSet[User, NormalizedURI] {
  def getPublicList: Option[URIList]
  def getPrivateList: Option[URIList]
}

object UserToUriEdgeSet {
  def apply(sourceId: Id[User], uriList: URIList, isPublicEdgeSet: Boolean): UserToUriEdgeSet = {
    val set = LongArraySet.fromSorted(uriList.ids)
    new UserToUriEdgeSet(sourceId) with LongArraySetEdgeSet[User, NormalizedURI] {
      override val longArraySet = set

      override def getPublicList = if (isPublicEdgeSet) Some(uriList) else None
      override def getPrivateList = if (isPublicEdgeSet) None else Some(uriList)

      override def accessor = new LuceneBackedBookmarkInfoAccessor(this, longArraySet) {
        override def createdAtByIndex(idx: Int): Long = {
          val datetime = uriList.createdAt(idx)
          Util.unitToMillis(datetime)
        }
        override def isPublicByIndex(idx: Int): Boolean = isPublicEdgeSet
        override def bookmarkIdByIndex(idx: Int): Long = throw new UnsupportedOperationException
      }
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

      new UserToUriEdgeSet(sourceId) with LongArraySetEdgeSet[User, NormalizedURI] {
        override val longArraySet = set
        override def getPublicList = Some(publicList)
        override def getPrivateList = Some(privateList)

        override def accessor = new LuceneBackedBookmarkInfoAccessor(this, longArraySet) {

          override def createdAtByIndex(idx: Int): Long = {
            val datetime = if (idx < pubListSize) publicList.createdAt(idx) else privateList.createdAt(idx - pubListSize)
            Util.unitToMillis(datetime)
          }
          override def isPublicByIndex(idx: Int): Boolean = (idx < pubListSize)
          override def bookmarkIdByIndex(idx: Int): Long = throw new UnsupportedOperationException
        }
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
    new UserToUriEdgeSet(sourceId) with LongArraySetEdgeSet[User, NormalizedURI] {
      override protected val longArraySet = set
      override def getPublicList = Some(publicList)
      override def getPrivateList = Some(privateList)

      override def accessor = new LuceneBackedBookmarkInfoAccessor(this, longArraySet) {
        override protected def createdAtByIndex(idx: Int): Long = {
          val datetime = if (idx < pubListSize) publicList.createdAt(idx) else privateList.createdAt(idx - pubListSize)
          Util.unitToMillis(datetime)
        }
        override protected def isPublicByIndex(idx: Int): Boolean = (idx < pubListSize)
        override protected def bookmarkIdByIndex(idx: Int): Long = bookmarkIds(idx)
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
      override val sourceFieldName = uriField
    }
  }
}
