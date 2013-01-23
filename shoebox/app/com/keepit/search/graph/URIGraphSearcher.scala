package com.keepit.search.graph

import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, User, UserCxRepo}
import com.keepit.search.graph.EdgeSetUtil._
import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.index.CachingIndexReader
import com.keepit.search.index.IdMapper
import com.keepit.search.index.Searcher
import com.keepit.search.line.LineIndexReader
import com.keepit.search.query.QueryUtil
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Query
import org.apache.lucene.search.IndexSearcher
import scala.collection.immutable.LongMap

class URIGraphSearcher(searcher: Searcher) {

  def getUserToUserEdgeSet(sourceId: Id[User], destIdSet: Set[Id[User]]) = new UserToUserEdgeSet(sourceId, destIdSet)

  def getUriToUserEdgeSet(sourceId: Id[NormalizedURI]) = new UriToUserEdgeSet(sourceId, searcher)

  def getUserToUriEdgeSet(sourceId: Id[User], publicOnly: Boolean = true) = {
    val uriIdSet = getURIList(sourceId) match {
      case Some(uriList) =>
        if (publicOnly) uriList.publicList.map(id => new Id[NormalizedURI](id)).toSet
        else (uriList.publicList.map(id => new Id[NormalizedURI](id)).toSet ++
              uriList.privateList.map(id => new Id[NormalizedURI](id)).toSet)
      case None => Set.empty[Id[NormalizedURI]]
    }
    new UserToUriEdgeSet(sourceId, uriIdSet)
  }

  def getUserToUriEdgeSetWithCreatedAt(sourceId: Id[User], publicOnly: Boolean = true) = {
    val uriIdMap = getURIList(sourceId) match {
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

  def getURIList(user: Id[User]): Option[URIList] = {
    val term = URIGraph.userTerm.createTerm(user.toString)
    var uriList: Option[URIList] = None
    val tp = searcher.indexReader.termPositions(term)
    try {
      if (tp.next()) {
        tp.nextPosition()
        val payloadLen = tp.getPayloadLength
        if (payloadLen > 0) {
          val payloadBuffer = new Array[Byte](payloadLen)
          tp.getPayload(payloadBuffer, 0)
          uriList = Some(new URIList(payloadBuffer))
        }
      }
    } finally {
      tp.close()
    }
    uriList
  }

  def getIndexReader(user: Id[User], uriList: URIList, terms: Set[Term]) = {
    val term = URIGraph.userTerm.createTerm(user.toString)
    val td = searcher.indexReader.termDocs(term)
    val userDocId = try {
      if (td.next()) td.doc else DocIdSetIterator.NO_MORE_DOCS
    } finally {
      td.close()
    }
    val numDocs = uriList.publicListSize + uriList.privateListSize
    LineIndexReader(searcher.indexReader, userDocId, terms, numDocs)
  }

  def openPersonalIndex(user: Id[User], query: Query): Option[(CachingIndexReader, IdMapper)] = {
    getURIList(user).map{ uriList =>
      val terms = QueryUtil.getTerms(query)
      (getIndexReader(user, uriList, terms), new ArrayIdMapper(uriList.publicList ++ uriList.privateList))
    }
  }

  def search(user: Id[User], query: Query): Map[Long, Float] = {
    var result = LongMap.empty[Float]
    getURIList(user).foreach{ uriList =>
      val publicList = uriList.publicList
      val privateList = uriList.privateList

      val lineIndexReader = getIndexReader(user, uriList, QueryUtil.getTerms(query))
      val rewrittenQuery = query.rewrite(lineIndexReader)
      val lineSearcher = new IndexSearcher(lineIndexReader)
      var weight = lineSearcher.createNormalizedWeight(rewrittenQuery)
      if (weight != null) {
        var scorer = weight.scorer(lineIndexReader, true, true)
        if (scorer != null) {
          var doc = scorer.nextDoc()
          while (doc < DocIdSetIterator.NO_MORE_DOCS) {
            if (doc < publicList.length) {
              val id = publicList(doc)
              result += (id -> scorer.score())
            } else if (doc < publicList.length + privateList.length) {
              val id = privateList(doc - publicList.length)
              result += (id -> scorer.score())
            }
            doc = scorer.nextDoc()
          }
        }
      }
    }
    result
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
  def createSourceTerm = URIGraph.uriTerm.createTerm(sourceId.toString)
}
