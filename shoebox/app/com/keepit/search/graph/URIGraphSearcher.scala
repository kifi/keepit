package com.keepit.search.graph

import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, User}
import com.keepit.search.graph.EdgeSetUtil._
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

class URIGraphSearcher(searcher: Searcher) {

  def reader: WrappedSubReader = searcher.indexReader.asAtomicReader

  def getUserToUserEdgeSet(sourceId: Id[User], destIdSet: Set[Id[User]]) = new UserToUserEdgeSet(sourceId, destIdSet)

  def getUriToUserEdgeSet(sourceId: Id[NormalizedURI]) = new UriToUserEdgeSet(sourceId, searcher)

  def getUserToUriEdgeSet(sourceId: Id[User], publicOnly: Boolean = true) = {
    val sourceDocId = reader.getIdMapper.getDocId(sourceId.id)
    val uriIdSet = getURIList(sourceDocId) match {
      case Some(uriList) =>
        if (publicOnly) LongArraySet.fromSorted(uriList.publicList)
        else LongArraySet.from(concat(uriList.publicList, uriList.privateList))
      case None => Set.empty[Long]
    }
    new UserToUriEdgeSet(sourceId, uriIdSet)
  }

  def getUserToUriEdgeSetWithCreatedAt(sourceId: Id[User], publicOnly: Boolean = true) = {
    val sourceDocId = reader.getIdMapper.getDocId(sourceId.id)
    val uriIdMap = getURIList(sourceDocId) match {
      case Some(uriList) =>
        if (publicOnly) LongToLongArrayMap.fromSorted(uriList.publicList, uriList.publicCreatedAt)
        else {
          LongToLongArrayMap.from(concat(uriList.publicList, uriList.privateList),
                                  concat(uriList.publicCreatedAt, uriList.privateCreatedAt))
        }
      case None => Map.empty[Long, Long]
    }
    new UserToUriEdgeSetWithCreatedAt(sourceId, uriIdMap)
  }

  def intersect(friends: UserToUserEdgeSet, bookmarkUsers: UriToUserEdgeSet): UserToUserEdgeSet = {
    val iter = intersect(friends.getDestDocIdSetIterator(searcher), bookmarkUsers.getDestDocIdSetIterator(searcher))
    val idMapper = searcher.indexReader.getIdMapper
    val destIdSet = iter.map{ idMapper.getId(_) }.map{ new Id[User](_) }.toSet
    new UserToUserEdgeSet(friends.sourceId, destIdSet)
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
        i.docID()
      }
      def advance(target: Int) = {
        var di = i.advance(target)
        var dj = j.advance(target)
        while (di != dj) {
          if (di < dj) di = i.advance(dj)
          else dj = j.advance(di)
        }
        i.docID()
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

  private def getURIList(userDocId: Int): Option[URIList] = {
    var uriList: Option[URIList] = None

    if (userDocId >= 0) {
      var docValues = reader.getBinaryDocValues(userField)
      var ref = new BytesRef()
      if (docValues != null) {
        docValues.get(userDocId, ref)
        if (ref.length > 0) {
          uriList = Some(new URIList(ref.bytes, ref.offset, ref.length))
        }
      }
    }
    uriList
  }

  private def getIndexReader(userDocId: Int, uriList: URIList, terms: Set[Term]) = {
    val numDocs = uriList.publicListSize + uriList.privateListSize
    LineIndexReader(reader, userDocId, terms, numDocs)
  }

  def openPersonalIndex(user: Id[User], query: Query): Option[(CachingIndexReader, IdMapper)] = {
    val userDocId = reader.getIdMapper.getDocId(user.id)

    getURIList(userDocId).map{ uriList =>
      val terms = QueryUtil.getTerms(query)
      (getIndexReader(userDocId, uriList, terms), new ArrayIdMapper(concat(uriList.publicList, uriList.privateList)))
    }
  }

  private def concat(a: Array[Long], b: Array[Long]): Array[Long] = {
    val ret = new Array[Long](a.length + b.length)
    System.arraycopy(a, 0, ret, 0, a.length)
    System.arraycopy(b, 0, ret, a.length, b.length)
    ret
  }

  private def concatAll(a: Array[Long]*): Array[Long] =  {
    val size = if (a.length > 0) a.map{ _.length }.sum else 0
    val ret = new Array[Long](size)
    var offset = 0
    var i = 0
    while (i < a.length) {
      val len = a(i).length
      System.arraycopy(a(i), 0, ret, offset, len)
      offset += len
      i += 1
    }
    ret
  }
}

class UserToUserEdgeSet(sourceId: Id[User], override val destIdSet: Set[Id[User]]) extends MaterializedEdgeSet[User, User](sourceId) {
  override lazy val destIdLongSet: Set[Long] = destIdSet.map(_.id)
  def size = destIdSet.size
}

class UserToUriEdgeSet(sourceId: Id[User], override val destIdLongSet: Set[Long]) extends MaterializedEdgeSet[User, NormalizedURI](sourceId) {
  override lazy val destIdSet: Set[Id[NormalizedURI]] = destIdLongSet.map(Id[NormalizedURI](_))
  def size = destIdLongSet.size
}

class UserToUriEdgeSetWithCreatedAt(sourceId: Id[User], destIdMap: Map[Long, Long])
  extends MaterializedEdgeSet[User, NormalizedURI](sourceId) {

  override lazy val destIdLongSet: Set[Long] = destIdMap.keySet
  override lazy val destIdSet: Set[Id[NormalizedURI]] = destIdLongSet.map(new Id[NormalizedURI](_))
  def size = destIdMap.size

  def getCreatedAt(id: Id[NormalizedURI]): Long = URIList.unitToMillis(destIdMap.get(id.id).getOrElse(0L))
}

class UriToUserEdgeSet(sourceId: Id[NormalizedURI], searcher: Searcher) extends LuceneBackedEdgeSet[NormalizedURI, User](sourceId, searcher) {
  def toId(id: Long) = new Id[User](id)
  def createSourceTerm = new Term(uriField, sourceId.toString)
}
