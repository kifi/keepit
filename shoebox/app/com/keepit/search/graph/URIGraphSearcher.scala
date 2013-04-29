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
        if (publicOnly) uriList.publicList.map(id => new Id[NormalizedURI](id)).toSet
        else (uriList.publicList.map(id => new Id[NormalizedURI](id)).toSet ++
              uriList.privateList.map(id => new Id[NormalizedURI](id)).toSet)
      case None => Set.empty[Id[NormalizedURI]]
    }
    new UserToUriEdgeSet(sourceId, uriIdSet)
  }

  def getUserToUriEdgeSetWithCreatedAt(sourceId: Id[User], publicOnly: Boolean = true) = {
    val sourceDocId = reader.getIdMapper.getDocId(sourceId.id)
    val uriIdMap = getURIList(sourceDocId) match {
      case Some(uriList) =>
        var m = uriList.publicList.zip(uriList.publicCreatedAt).foldLeft(Map.empty[Id[NormalizedURI], Long]) {
          case (m, (id, createdAt)) => m + (Id[NormalizedURI](id) -> createdAt)
        }
        if (publicOnly) m
        else {
          uriList.privateList.zip(uriList.privateCreatedAt).foldLeft(m) {
            case (m, (id, createdAt)) => m + (Id[NormalizedURI](id) -> createdAt)
          }
        }
      case None => Map.empty[Id[NormalizedURI], Long]
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
      (getIndexReader(userDocId, uriList, terms), new ArrayIdMapper(uriList.publicList ++ uriList.privateList))
    }
  }
}

class UserToUserEdgeSet(sourceId: Id[User], destIdSet: Set[Id[User]]) extends MaterializedEdgeSet[User, User](sourceId, destIdSet)

class UserToUriEdgeSet(sourceId: Id[User], destIdSet: Set[Id[NormalizedURI]]) extends MaterializedEdgeSet[User, NormalizedURI](sourceId, destIdSet)

class UserToUriEdgeSetWithCreatedAt(sourceId: Id[User], destIdMap: Map[Id[NormalizedURI], Long])
  extends MaterializedEdgeSet[User, NormalizedURI](sourceId, destIdMap.keySet) {

  def getCreatedAt(id: Id[NormalizedURI]): Long = URIList.unitToMillis(destIdMap.get(id).getOrElse(0L))
}

class UriToUserEdgeSet(sourceId: Id[NormalizedURI], searcher: Searcher) extends LuceneBackedEdgeSet[NormalizedURI, User](sourceId, searcher) {
  def toId(id: Long) = new Id[User](id)
  def createSourceTerm = new Term(uriField, sourceId.toString)
}
