package com.keepit.search.graph

import com.keepit.common.logging.Logging
import com.keepit.search.Searcher
import com.keepit.search.index.WrappedSubReader
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.BytesRef
import scala.collection.mutable.ArrayBuffer

class BaseGraphSearcher(searcher: Searcher) extends Logging {

  protected val reader: WrappedSubReader = searcher.indexReader.asAtomicReader

  def getDocId(id: Long) = reader.getIdMapper.getDocId(id)

  def getURIList(field: String, docid: Int): URIList = {
    if (docid < 0) return URIList.empty
    val allBytes = getAllBytes(field, docid)
    URIList(allBytes.toArray, 0, allBytes.length)
  }

  def getLongArray(field: String, docid: Int): Array[Long] = {
    if (docid < 0) return Array.empty[Long]
    val allBytes = getAllBytes(field, docid)
    Util.unpackLongArray(allBytes, 0, allBytes.length)
  }

  private def getAllBytes(field: String, docid: Int): Array[Byte] = {
    import com.keepit.search.index.Indexable._

    val allBytes = new ArrayBuffer[Byte]()
    var done = false
    var iter = 0

     while (!done){
      val fieldName = field + numberSuffix(iter)
      val docValues = reader.getBinaryDocValues(fieldName)
      if (docValues != null){
        var ref = new BytesRef()
        docValues.get(docid, ref)
        if (ref.length > 0){
          val offset = ref.offset
          if (ref.length == MAX_BINARY_FIELD_LENGTH){
            // there is something left. extra byte is EOF symbol and is dropped
            for( j <- 0 until (ref.length -1)){
              allBytes.append(ref.bytes(offset + j))
            }
          } else{
            for(j <- 0 until ref.length){
              allBytes.append(ref.bytes(offset + j))                 // all bytes are good
            }
            done = true                                              // nothing left
          }
        } else {
          log.error(s"missing uri list data: ${field + numberSuffix(iter)}")
        }
        iter += 1
      } else {
        done = true
      }
    }
    allBytes.toArray
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
