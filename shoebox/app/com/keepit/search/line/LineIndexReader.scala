package com.keepit.search.line

import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, User}
import com.keepit.search.index.IdMapper
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Query
import org.apache.lucene.index.Term
import scala.collection.immutable.LongMap
import java.util.{Map => JMap}
import org.apache.lucene.document.FieldSelector
import org.apache.lucene.index.TermVectorMapper
import org.apache.lucene.index.TermEnum
import org.apache.lucene.index.TermDocs
import org.apache.lucene.index.TermPositions

class LineIndexReader(indexReader: IndexReader, userDocId: Int, numLines: Int, idMapper: Option[IdMapper]) extends IndexReader {

  private[this] var invertedLists = Map.empty[Term, InvertedList]

  def getInvertedList(term: Term) = {
    invertedLists.get(term) match {
      case Some(list) => list
      case None =>
        val tp = indexReader.termPositions(term)
        val invertedList = if (tp.skipTo(userDocId) && tp.doc() == userDocId) {
          val freq = tp.freq()
          val list = new Array[Int](freq)
          var i = 0
          var docFreq = 0
          var curDoc = -1
          while (i < freq) {
            val pos = tp.nextPosition()
            val docid = pos / LineField.MAX_POSITION_PER_LINE
            if (docid != curDoc) {
              curDoc = docid
              docFreq += 1
            }
            list(i) = pos
            i += 1
          }
          val docPtr = new Array[Int](docFreq + 1)
          var d = 0
          i = 0
          curDoc = -1
          while (i < freq) {
            val pos = list(i)
            val docid = pos / LineField.MAX_POSITION_PER_LINE
            if (docid != curDoc) {
              curDoc = docid
              docPtr(d + 1) = docPtr(d)
              d += 1
            }
            docPtr(d) += 1
            i += 1
          }
          new InvertedList(docPtr, list)
        } else {
          EmptyInvertedList
        }
        invertedLists += (term -> invertedList)
        invertedList
    }
  }

  override def docFreq(term: Term) = getInvertedList(term).docPtr.length - 1
  override def doClose() {}
  override def hasDeletions() = false
  override def isDeleted(docid: Int) = false
  override def maxDoc() = numLines
  override def hasNorms(field: String) = false
  override def numDocs() = numLines
  override def termDocs() = (new LineTermPositions(this)).asInstanceOf[TermDocs]
  override def termPositions() = (new LineTermPositions(this)).asInstanceOf[TermPositions]

  override def norms(field: String) = null
  override def norms(field: String, bytes: Array[Byte], offset: Int) {}
  override def doCommit(commitUserData: JMap[String, String]) = throw new UnsupportedOperationException()
  override def document(doc: Int, fieldSelector: FieldSelector) = throw new UnsupportedOperationException()
  override def doDelete(doc: Int) = throw new UnsupportedOperationException()
  override def doSetNorm(doc: Int, field: String, value: Byte) = throw new UnsupportedOperationException()
  override def doUndeleteAll() = throw new UnsupportedOperationException()
  override def getFieldInfos() = throw new UnsupportedOperationException()
  override def getTermFreqVector(doc: Int, field: String) = throw new UnsupportedOperationException()
  override def getTermFreqVector(doc: Int, mapper: TermVectorMapper) = throw new UnsupportedOperationException()
  override def getTermFreqVector(doc: Int, field: String, mapper: TermVectorMapper) = throw new UnsupportedOperationException()
  override def getTermFreqVectors(doc: Int) = throw new UnsupportedOperationException()
  override def terms() = throw new UnsupportedOperationException()
  override def terms(term: Term) = throw new UnsupportedOperationException()
}

class InvertedList(val docPtr: Array[Int], val list: Array[Int])
object EmptyInvertedList extends InvertedList(Array[Int](0), Array.empty[Int])

class LineTermPositions(indexReader: LineIndexReader) extends TermPositions {
  protected var invertedList: InvertedList = EmptyInvertedList
  protected var docid = -1
  protected var ptrDoc = -1
  protected var ptrPos = -1

  def close() {}

  def doc() = docid

  def freq() = {
    invertedList.docPtr(ptrDoc + 1) - invertedList.docPtr(ptrDoc)
  }

  def next() = {
    if ((ptrDoc + 1) < (invertedList.docPtr.length - 1)) { // docPtr has +1
      ptrDoc += 1
      ptrPos = invertedList.docPtr(ptrDoc)
      docid = invertedList.list(ptrPos) / LineField.MAX_POSITION_PER_LINE
      true
    } else {
      docid = DocIdSetIterator.NO_MORE_DOCS
      false
    }
  }

  def read(docs: Array[Int], freqs: Array[Int]): Int = {
    var i = 0
    while (i < docs.length) {
      if (next()) {
        docs(i) = doc()
        freqs(i) = freq()
        i += 1
      } else {
        return i
      }
    }
    i
  }

  def seek(term: Term) {
    invertedList = indexReader.getInvertedList(term)
  }

  def seek(termEnum: TermEnum) {
    seek(termEnum.term())
  }

  def skipTo(did: Int): Boolean = {
    while (docid < did) {
      if (!next()) return false
    }
    docid < DocIdSetIterator.NO_MORE_DOCS
  }

  def nextPosition() = {
    val pos = invertedList.list(ptrPos) % LineField.MAX_POSITION_PER_LINE
    ptrPos += 1
    pos
  }
  // no payload
  def getPayloadLength() = 0
  def getPayload(data: Array[Byte], offset: Int) = data
  def isPayloadAvailable() = false
}