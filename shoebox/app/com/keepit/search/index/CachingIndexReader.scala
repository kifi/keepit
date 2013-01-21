package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.{NormalizedURI, User}
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Query
import org.apache.lucene.index.Term
import scala.collection.immutable.LongMap
import scala.collection.mutable.{Map => MutableMap}
import java.util.{Map => JMap}
import java.util.Arrays
import org.apache.lucene.document.FieldSelector
import org.apache.lucene.index.TermVectorMapper
import org.apache.lucene.index.TermEnum
import org.apache.lucene.index.TermDocs
import org.apache.lucene.index.TermPositions
import scala.collection.mutable.ArrayBuffer

class CachingIndexReader(val invertedLists: Map[Term, InvertedList]) extends IndexReader with Logging {

  var numDocs: Int = 0
  var maxDoc: Int = 0

  def split(remappers: Map[String, DocIdRemapper]): Map[String, CachingIndexReader] = {
    var subReaders = Map.empty[String, CachingIndexReader]

    def split(invertedLists: Map[Term, InvertedList], name: String, remapper: DocIdRemapper) = {
      var remapped = Map.empty[Term, InvertedList]
      var remainder = Map.empty[Term, InvertedList]
      invertedLists.foreach{ case (t, l) =>
        val (list1, list2) = l.split(remapper)
        remapped += (t -> list1)
        remainder += (t -> list2)
      }
      subReaders += (name -> new CachingIndexReader(remapped))
      remainder
    }

    val remainder = remappers.foldLeft(invertedLists){ case (remainder, (name, remapper)) =>
      split(remainder, name, remapper)
    }
    subReaders += ("" -> new CachingIndexReader(remainder))
    subReaders
  }

  override def docFreq(term: Term) = {
    val list = invertedLists.get(term) match {
      case Some(list) => list
      case None =>
        log.debug("term % not found")
        EmptyInvertedList
    }
    list.docFreq
  }
  override def doClose() {}
  override def hasDeletions() = false
  override def isDeleted(docid: Int) = false
  override def hasNorms(field: String) = false
  override def termDocs() = (new CachedTermPositions(this)).asInstanceOf[TermDocs]
  override def termPositions() = (new CachedTermPositions(this)).asInstanceOf[TermPositions]

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

class InvertedList(val dlist: Array[Int], val plist: Array[Array[Int]]) {
  def docFreq = dlist.length

  def split(remapper: DocIdRemapper) = {
    val remapped = new InvertedListBuilder()
    val remainder = new InvertedListBuilder()
    var i = 0
    while (i < dlist.length) {
      val newDID = remapper.src2dst(dlist(i))
      if (newDID >= 0) remapped.add(newDID, plist(i))
      else remainder.add(dlist(i), plist(i))
      i += 1
    }
    (remapped.build, remainder.build)
  }
}

object EmptyInvertedList extends InvertedList(Array.empty[Int], Array.empty[Array[Int]])

class InvertedListBuilder() {
  private[this] lazy val dlist = new ArrayBuffer[Int]
  private[this] lazy val plist = new ArrayBuffer[Array[Int]]

  def add(docid: Int, positions: Array[Int]) {
    dlist += docid
    plist += positions
  }

  def build = new InvertedList(dlist.toArray, plist.toArray)
}

class CachedTermPositions(indexReader: CachingIndexReader) extends TermPositions {
  private[this] var docFreq = 0
  private[this] var docid = -1
  private[this] var dlist = Array.empty[Int]
  private[this] var plist = Array.empty[Array[Int]]
  private[this] var positions = Array.empty[Int]
  private[this] var ptrDoc = 0
  private[this] var ptrPos = 0

  def close() {}

  def doc() = docid

  def freq() = positions.length

  def next() = {
    if (ptrDoc < docFreq) {
      docid = dlist(ptrDoc)
      positions = plist(ptrDoc)
      ptrPos = 0
      ptrDoc += 1
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
    val invertedList = indexReader.invertedLists.getOrElse(term, EmptyInvertedList)
    dlist = invertedList.dlist
    plist = invertedList.plist
    docFreq = invertedList.docFreq
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
    val pos = positions(ptrPos)
    ptrPos += 1
    pos
  }
  // no payload
  def getPayloadLength() = 0
  def getPayload(data: Array[Byte], offset: Int) = data
  def isPayloadAvailable() = false
}