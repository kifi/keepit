package com.keepit.search.index

import org.apache.lucene.document.FieldSelector
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.MultiReader
import org.apache.lucene.index.SegmentReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermVectorMapper
import scala.collection.mutable.ArrayBuffer
import java.util.{Map => JMap}

object WrappedIndexReader {

  def apply(inner: IndexReader): WrappedIndexReader = {
    doOpen(inner, Map.empty[String, IdMapper])
  }

  def reopen(oldReader: WrappedIndexReader): WrappedIndexReader = {
    val oldInner = oldReader.inner
    val newInner = IndexReader.openIfChanged(oldInner)
    if (newInner != null) {
      var oldIdMappers = oldReader.wrappedSubReaders.foldLeft(Map.empty[String, IdMapper]){ (m, r) => m + (r.name -> r.getIdMapper) }
      doOpen(newInner, oldIdMappers)
    } else {
      oldReader
    }
  }

  def apply(inner: IndexReader, subReaders: ArrayBuffer[WrappedSubReader]): WrappedIndexReader = {
    val seqSubReaders = subReaders.map{ _.asInstanceOf[IndexReader] }.toArray
    new WrappedIndexReader(inner, subReaders.toArray, seqSubReaders)
  }

  private def doOpen(inner: IndexReader, oldIdMappers: Map[String, IdMapper]) = {
    val subReaders = inner.getSequentialSubReaders
    var i = 0
    val newSubReaders = subReaders.foldLeft(new ArrayBuffer[WrappedSubReader]){
      case (buf, segmentReader: SegmentReader) =>
        val segmentName = segmentReader.getSegmentName
        buf += new WrappedSubReader(segmentName, segmentReader, oldIdMappers.getOrElse(segmentName, ArrayIdMapper(segmentReader)))
      case (buf, subReader) => throw new IllegalStateException("not insance of %s but %s".format(classOf[SegmentReader].getName(), subReader.getClass.getName))
    }
    apply(inner, newSubReaders)
  }
}

class WrappedIndexReader(val inner: IndexReader, val wrappedSubReaders: Array[WrappedSubReader], sequentialSubReaders: Array[IndexReader])
extends MultiReader(sequentialSubReaders, false) {

  override def getSequentialSubReaders() = sequentialSubReaders

  def getIdMapper: IdMapper = {
    new IdMapper {
      def getId(docid: Int): Long = {
        var base = 0
        var i = 0
        while (i < wrappedSubReaders.length) {
          val r = wrappedSubReaders(i)
          val nextBase = base + r.maxDoc
          if (docid < nextBase) return r.getIdMapper.getId(docid - base)
          base = nextBase
          i += 1
        }
        throw new IllegalStateException("failed to find docid: %d".format(docid))
      }
      def getDocId(id: Long): Int = {
        var base = 0
        var i = 0
        while (i < subReaders.length) {
          val r = wrappedSubReaders(i)
          val nextBase = base + r.maxDoc
          val docid = r.getIdMapper.getDocId(id)
          if (docid >= 0 && !r.isDeleted(docid)) return docid + base
          base = nextBase
          i += 1
        }
        -1
      }
    }
  }

  def add(indexReader: CachingIndexReader, idMapper: IdMapper) = {
    val remappers = wrappedSubReaders.foldLeft(Map.empty[String, DocIdRemapper]){ (m, r) => m + (r.name -> new DocIdRemapper(idMapper, r.getIdMapper)) }
    val splitReaders = indexReader.split(remappers)
    var newSubReaders = ArrayBuffer.empty[WrappedSubReader]
    wrappedSubReaders.foreach{ r =>
      newSubReaders += new WrappedSubReader(r.name, new PersonalizedIndexReader(r, splitReaders(r.name)), r.getIdMapper)
    }
    splitReaders.get("").foreach{ subReader =>
      newSubReaders += new WrappedSubReader("", subReader, idMapper)
    }

    WrappedIndexReader(inner, newSubReaders)
  }

  override def doDelete(doc: Int) = throw new UnsupportedOperationException()
  override def doUndeleteAll() = throw new UnsupportedOperationException()
  override def doCommit(commitUserData: JMap[String, String]) = throw new UnsupportedOperationException()
  override def doSetNorm(doc: Int, field: String, value: Byte) = throw new UnsupportedOperationException()
  override protected def doOpenIfChanged() = throw new UnsupportedOperationException()
}

class WrappedSubReader(val name: String, val inner: IndexReader, idMapper: IdMapper) extends IndexReader {
  def getIdMapper = idMapper

  override def getSequentialSubReaders() = null
  override def docFreq(term: Term) = inner.docFreq(term)
  override def doClose() = { inner.close() }
  override def hasDeletions() = inner.hasDeletions()
  override def isDeleted(docid: Int) = inner.isDeleted(docid)
  override def hasNorms(field: String) = inner.hasNorms(field)
  override def termDocs() = inner.termDocs()
  override def termPositions() = inner.termPositions()
  override def norms(field: String) = inner.norms(field)
  override def norms(field: String, bytes: Array[Byte], offset: Int) = inner.norms(field, bytes, offset)
  override def document(doc: Int, fieldSelector: FieldSelector) = inner.document(doc, fieldSelector)
  override def getFieldInfos() = inner.getFieldInfos()
  override def getTermFreqVector(doc: Int, field: String) = inner.getTermFreqVector(doc, field)
  override def getTermFreqVector(doc: Int, mapper: TermVectorMapper) = inner.getTermFreqVector(doc, mapper)
  override def getTermFreqVector(doc: Int, field: String, mapper: TermVectorMapper) = inner.getTermFreqVector(doc, field, mapper)
  override def getTermFreqVectors(doc: Int) = inner.getTermFreqVectors(doc)
  override def terms() = inner.terms()
  override def terms(term: Term) = inner.terms(term)
  override def maxDoc() = inner.maxDoc()
  override def numDocs() = inner.numDocs()

  override def doDelete(doc: Int) = throw new UnsupportedOperationException()
  override def doUndeleteAll() = throw new UnsupportedOperationException()
  override def doCommit(commitUserData: JMap[String, String]) = throw new UnsupportedOperationException()
  override def doSetNorm(doc: Int, field: String, value: Byte) = throw new UnsupportedOperationException()
}
