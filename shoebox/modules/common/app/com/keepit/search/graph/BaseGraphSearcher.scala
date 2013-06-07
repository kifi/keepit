package com.keepit.search.graph

import com.keepit.common.logging.Logging
import com.keepit.search.index.Searcher
import com.keepit.search.index.WrappedSubReader
//import com.keepit.search.line.LineIndexReader
//import com.keepit.search.query.QueryUtil
//import com.keepit.search.util.LongArraySet
//import com.keepit.search.util.LongToLongArrayMap
//import org.apache.lucene.index.IndexReader
//import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
//import org.apache.lucene.search.Query
import org.apache.lucene.util.BytesRef
//import scala.collection.mutable.ArrayBuffer

class BaseGraphSearcher(searcher: Searcher) extends Logging {

  protected val reader: WrappedSubReader = searcher.indexReader.asAtomicReader

  def getDocId(id: Long) = reader.getIdMapper.getDocId(id)

  def getURIList(field: String, docid: Int): URIList = {
    if (docid >= 0) {
      var docValues = reader.getBinaryDocValues(field)
      if (docValues != null) {
        var ref = new BytesRef()
        docValues.get(docid, ref)
        if (ref.length > 0) {
          return URIList(ref.bytes, ref.offset, ref.length)
        } else {
          log.error(s"missing uri list data: ${field}")
        }
      }
    }
    URIList.empty
  }

  def getLongArray(field: String, docid: Int): Array[Long] = {
    if (docid >= 0) {
      var docValues = reader.getBinaryDocValues(field)
      if (docValues != null) {
        var ref = new BytesRef()
        docValues.get(docid, ref)
        if (ref.length > 0) {
          return URIList.unpackLongArray(ref.bytes, ref.offset, ref.length)
        } else {
          log.error(s"missing uri list data: ${field}")
        }
      }
    }
    Array.empty[Long]
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
}
