package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.DocsEnum
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.FieldInfo.IndexOptions
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.Fields
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.index.BinaryDocValues
import org.apache.lucene.index.SortedDocValues
import org.apache.lucene.index.SortedSetDocValues
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.Term
import org.apache.lucene.index.Terms
import org.apache.lucene.index.TermsEnum
import org.apache.lucene.index.TermsEnum.SeekStatus
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Query
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.FixedBitSet
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.collection.SortedMap
import scala.collection.SortedSet
import java.util.Arrays
import java.util.Comparator
import java.util.{Iterator=>JIterator}

class CachingIndexReader(val index: CachedIndex, liveDocs: FixedBitSet = null) extends AtomicReader with Logging {

  def split(remappers: Map[String, DocIdRemapper]): Map[String, CachingIndexReader] = {
    val (subReaders, remainder) = remappers.foldLeft((Map.empty[String, CachingIndexReader], index)){ case ((subReaders, index), (name, remapper)) =>
      val numDocsRemapped = remapper.numDocsRemapped
      val numDocsRemained = index.numDocs - numDocsRemapped

      var remapped = new CachedIndex(remapper.maxDoc, numDocsRemapped)
      var remainder = new CachedIndex(maxDoc, numDocsRemained)
      index.foreach{ (f, t, l) =>
        val (list1, list2) = l.split(remapper)
        remapped += (f, t, list1)
        remainder += (f, t, list2)
      }
      (subReaders + (name -> new CachingIndexReader(remapped)), remainder)
    }
    if (remainder.isEmpty) {
      subReaders
    } else {
      val bits = new FixedBitSet(maxDoc)
      remainder.foreach{ (_, _, list) =>  list.dlist.foreach{ case (d, _) => bits.set(d) } }
      subReaders + ("" -> new CachingIndexReader(remainder, bits))
    }
  }

  override def numDocs() = index.numDocs
  override def maxDoc() = index.maxDoc

  override def fields() = index.fields

  override def getFieldInfos(): FieldInfos = index.fieldInfos
  override def getLiveDocs(): Bits = liveDocs

  override def getTermVectors(doc: Int) = throw new UnsupportedOperationException()

  override def getNumericDocValues(field: String): NumericDocValues = null
  override def getBinaryDocValues(field: String): BinaryDocValues = null
  override def getSortedDocValues(field: String): SortedDocValues = null
  override def getSortedSetDocValues(field: String): SortedSetDocValues = null
  override def getNormValues(field: String): NumericDocValues = null
  override def hasDeletions() = false
  override def document(doc: Int, visitor: StoredFieldVisitor) = throw new UnsupportedOperationException()
  protected def doClose() {}
}

class InvertedList(val dlist: ArrayBuffer[(Int, Array[Int])]) {

  def docFreq = dlist.length

  def totalTermFreq = dlist.foldLeft(0){ case (sum, (doc, positions)) => sum + positions.length }

  def iterator = dlist.iterator

  def split(remapper: DocIdRemapper) = {
    val remapped = new InvertedListBuilder()
    val remainder = new InvertedListBuilder()
    var i = 0
    while (i < dlist.length) {
      val newDID = remapper.remap(dlist(i)._1)
      if (newDID >= 0) remapped.add(newDID, dlist(i)._2)
      else remainder.add(dlist(i))
      i += 1
    }
    (remapped.sortAndBuild, remainder.build)
  }

  override def toString() = {
    dlist.map{ case (doc, plist) =>
      "[d=%d,p=%s]".format(doc, plist.mkString("(", "," ,")"))
    }.mkString("InvertedList(", "," ,")")
  }
}

object EmptyInvertedList extends InvertedList(ArrayBuffer.empty[(Int, Array[Int])]) {
  val emptyPositions = Array.empty[Int]
}

class InvertedListBuilder() {
  private[this] lazy val buf = new ArrayBuffer[(Int, Array[Int])]
  private[this] var _isEmpty = true

  def isEmpty: Boolean = _isEmpty

  def add(docid: Int, positions: Array[Int]) {
    buf += ((docid, positions))
    _isEmpty = false
  }
  def add(doc: (Int, Array[Int])) {
    buf += doc
    _isEmpty = false
  }

  def sortAndBuild: InvertedList = {
    if (_isEmpty) {
      EmptyInvertedList
    } else {
      new InvertedList(buf.sortBy(_._1))
    }
  }

  def build: InvertedList = {
    if (_isEmpty) {
      EmptyInvertedList
    } else {
      new InvertedList(buf)
    }
  }
}

class CachedIndex(invertedLists: SortedMap[String, SortedMap[BytesRef, InvertedList]], val maxDoc: Int, val numDocs: Int) {
  def this(maxDoc: Int, numDocs: Int) = this(SortedMap.empty[String, SortedMap[BytesRef, InvertedList]], maxDoc, numDocs)

  def isEmpty = invertedLists.isEmpty

  def +(field: String, text: BytesRef, list: InvertedList): CachedIndex = {
    if (list.docFreq > 0) {
      val terms = invertedLists.getOrElse(field, SortedMap.empty[BytesRef, InvertedList])
      new CachedIndex(invertedLists + (field -> (terms + (text -> list))), maxDoc, numDocs)
    } else {
      this
    }
  }

  def foreach(f: (String, BytesRef, InvertedList) => Unit) = {
    invertedLists.foreach{ case (field, terms) =>
      terms.foreach{ case (text, list) => f(field, text, list) }
    }
  }

  def get(term: Term): Option[InvertedList] = invertedLists.get(term.field).flatMap{ _.get(term.bytes) }

  def apply(term: Term): InvertedList = get(term).getOrElse(EmptyInvertedList)

  lazy val fieldInfos: FieldInfos = {
    val infos = invertedLists.keys.zipWithIndex.map{ case (name, number) =>
      new FieldInfo(name, true, number, false, true, false,
                    IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, null, null, null)
    }.toArray
    new FieldInfos(infos)
  }

  def fields: Fields = new Fields {
    override def iterator(): JIterator[String] = invertedLists.keySet.iterator

    override def terms(field: String): Terms = {
      val termSet = invertedLists.getOrElse(field, SortedMap.empty[BytesRef, InvertedList])
      new CachedTerms(termSet, numDocs)
    }

    override def size() = invertedLists.size
  }

  override def toString() = s"CachedIndex(${invertedLists.toString})"
}

class CachedTerms(termMap: SortedMap[BytesRef, InvertedList], numDocs: Int) extends Terms {
  override def iterator(reuse: TermsEnum): TermsEnum = {
    new CachedTermsEnum(termMap: SortedMap[BytesRef, InvertedList])
  }

  override def size(): Long = termMap.size.toLong

  override def getSumTotalTermFreq(): Long = {
    termMap.values.foldLeft(0L){ (sum, list) => sum + list.totalTermFreq.toLong }
  }

  override def getSumDocFreq(): Long = {
    termMap.values.foldLeft(0L){ (sum, list) => sum + list.docFreq.toLong }
  }

  override def getDocCount(): Int = numDocs

  override def hasOffsets() = false

  override def hasPositions() = true

  override def hasPayloads() = false;

  override def getComparator(): Comparator[BytesRef] = null
}

class CachedTermsEnum(terms: SortedMap[BytesRef, InvertedList]) extends TermsEnum {

  private[this] var currentEntry: Option[(BytesRef, InvertedList)] = None
  private[this] var currentCollection = terms

  override def getComparator(): Comparator[BytesRef] = null

  override def seekCeil(text: BytesRef, useCache: Boolean): SeekStatus = {
    currentCollection = terms.from(text)
    currentEntry = currentCollection.headOption
    currentEntry.headOption match {
      case None => SeekStatus.END
      case Some((foundText, _)) => if (foundText.equals(text)) SeekStatus.FOUND else SeekStatus.NOT_FOUND
      case _ => SeekStatus.NOT_FOUND
    }
  }

  override def seekExact(ord: Long): Unit = throw new UnsupportedOperationException

  override def term(): BytesRef = currentEntry.get._1

  override def ord(): Long = throw new UnsupportedOperationException

  override def docFreq(): Int = currentEntry.get._2.docFreq

  override def totalTermFreq(): Long = currentEntry.get._2.totalTermFreq

  override def next(): BytesRef = {
    currentCollection = currentCollection.tail
    currentEntry = currentCollection.headOption
    currentEntry match {
      case Some(head) => head._1
      case None => null
    }
  }

  override def docs(liveDocs: Bits, reuse: DocsEnum, flags: Int): DocsEnum = {
    currentEntry match {
      case Some((_, list)) => new CachedDocsAndPositionsEnum(list)
      case None => emptyDocsAndPositionsEnum
    }
  }

  override def  docsAndPositions(liveDocs: Bits, reuse: DocsAndPositionsEnum, flags: Int): DocsAndPositionsEnum = {
    currentEntry match {
      case Some((_, list)) => new CachedDocsAndPositionsEnum(list)
      case None => emptyDocsAndPositionsEnum
    }
  }
}

class CachedDocsAndPositionsEnum(list: InvertedList) extends DocsAndPositionsEnum {
  private[this] val dlist = list.dlist
  private[this] var docFreq = list.docFreq
  private[this] var docid = -1
  private[this] var positions = EmptyInvertedList.emptyPositions
  private[this] var ptrDoc = 0
  private[this] var ptrPos = 0

  override def docID() = docid

  override def freq() = positions.length

  override def nextDoc(): Int = {
    if (ptrDoc < docFreq) {
      docid = dlist(ptrDoc)._1
      positions = dlist(ptrDoc)._2
      ptrPos = 0
      ptrDoc += 1
      docid
    } else {
      docid = DocIdSetIterator.NO_MORE_DOCS
      positions = EmptyInvertedList.emptyPositions
      docid
    }
  }

  override def advance(target: Int): Int = {
    do { nextDoc() } while (docid < target)
    docid
  }

  override def nextPosition(): Int = {
    val pos = positions(ptrPos)
    ptrPos += 1
    pos
  }

  override def startOffset(): Int = -1

  override def endOffset(): Int = -1

  override def getPayload(): BytesRef = null
}

