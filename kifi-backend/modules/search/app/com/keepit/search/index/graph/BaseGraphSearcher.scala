package com.keepit.search.index.graph

import com.keepit.common.logging.Logging
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.BytesRef
import scala.collection.mutable.ArrayBuffer
import scala.math.min

class BaseGraphSearcher(searcher: Searcher) extends Logging {

  protected val reader: WrappedSubReader = searcher.indexReader.asAtomicReader

  def getDocId(id: Long) = reader.getIdMapper.getDocId(id)

  def getURIList(field: String, docid: Int): URIList = {
    if (docid < 0) return URIList.empty
    val ref = getAllBytes(field, docid)
    URIList(ref.bytes, ref.offset, ref.length)
  }

  def getLongArray(field: String, docid: Int): Array[Long] = {
    if (docid < 0) return Array.empty[Long]
    val ref = getAllBytes(field, docid)
    Util.unpackLongArray(ref.bytes, ref.offset, ref.length)
  }

  private def getAllBytes(field: String, docid: Int): BytesRef = {
    import com.keepit.search.index.Indexable._

    val bytesRefs = new ArrayBuffer[BytesRef]()
    var done = false
    var iter = 0

    while (!done) {
      val fieldName = addNumberSuffix(field, iter)
      val docValues = reader.getBinaryDocValues(fieldName)
      if (docValues != null) {
        val ref = docValues.get(docid)
        if (ref.length > 0) {
          if (ref.length == MAX_BINARY_FIELD_LENGTH) {
            bytesRefs.append(new BytesRef(ref.bytes, ref.offset, ref.length - 1)) //last byte is EOF symbol and is dropped. We still have something left
          } else {
            bytesRefs.append(ref) // all bytes are good
            done = true // nothing left
          }
        } else {
          log.error(s"missing uri list data: $fieldName")
        }
        iter += 1
      } else {
        done = true
      }
    }

    bytesRefs.length match {
      case 0 => new BytesRef()
      case 1 => bytesRefs(0) // a single ref. do not copy bytes.
      case _ =>
        val counts = bytesRefs.foldLeft(0)((len, ref) => len + ref.length)
        val allBytes = new Array[Byte](counts)

        var offset = 0
        bytesRefs.foreach { ref =>
          System.arraycopy(ref.bytes, ref.offset, allBytes, offset, ref.length)
          offset += ref.length
        }
        new BytesRef(allBytes)
    }
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
      def cost(): Long = min(i.cost, j.cost)
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
