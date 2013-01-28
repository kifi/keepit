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
    val (subReaders, remainder) = remappers.foldLeft(Map.empty[String, CachingIndexReader], invertedLists){ case ((subReaders, invertedLists), (name, remapper)) =>
      var remapped = Map.empty[Term, InvertedList]
      var remainder = Map.empty[Term, InvertedList]
      invertedLists.foreach{ case (t, l) =>
        val (list1, list2) = l.split(remapper)
        remapped += (t -> list1)
        remainder += (t -> list2)
      }
      (subReaders + (name -> new CachingIndexReader(remapped)), remainder)
    }
    if (remainder.isEmpty) subReaders else subReaders + ("" -> new CachingIndexReader(remainder))
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

class InvertedList(val dlist: Array[(Int, Array[Int])]) {
  def docFreq = dlist.length

  def split(remapper: DocIdRemapper) = {
    val remapped = new InvertedListBuilder()
    val remainder = new InvertedListBuilder()
    var i = 0
    while (i < dlist.length) {
      val newDID = remapper.src2dst(dlist(i)._1)
      if (newDID >= 0) remapped.add(newDID, dlist(i)._2)
      else remainder.add(dlist(i))
      i += 1
    }
    (remapped.build, remainder.build)
  }
}

object EmptyInvertedList extends InvertedList(Array.empty[(Int, Array[Int])]) {
  val emptyPositions = Array.empty[Int]
}

class InvertedListBuilder() {
  private[this] lazy val buf = new ArrayBuffer[(Int, Array[Int])]
  private[this] var isEmpty = true

  def add(docid: Int, positions: Array[Int]) {
    buf += ((docid, positions))
    isEmpty = false
  }
  def add(doc: (Int, Array[Int])) {
    buf += doc
    isEmpty = false
  }

  def build = if (isEmpty) EmptyInvertedList else new InvertedList(buf.sortBy(_._1).toArray)
}

class CachedTermPositions(indexReader: CachingIndexReader) extends TermPositions {
  private[this] var docFreq = 0
  private[this] var docid = -1
  private[this] var dlist = EmptyInvertedList.dlist
  private[this] var positions = EmptyInvertedList.emptyPositions
  private[this] var ptrDoc = 0
  private[this] var ptrPos = 0

  def close() {}

  def doc() = docid

  def freq() = positions.length

  def next() = {
    if (ptrDoc < docFreq) {
      docid = dlist(ptrDoc)._1
      positions = dlist(ptrDoc)._2
      ptrPos = 0
      ptrDoc += 1
      true
    } else {
      docid = DocIdSetIterator.NO_MORE_DOCS
      positions = EmptyInvertedList.emptyPositions
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
    docFreq = invertedList.docFreq
  }

  def seek(termEnum: TermEnum) {
    seek(termEnum.term())
  }

  def skipTo(target: Int): Boolean = {
    var ret = false
    do { ret = next() } while (ret & docid < target)
    ret
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
