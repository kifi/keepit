package com.keepit.search.index

import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.MultiReader
import org.apache.lucene.index.SegmentReader
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.index.BinaryDocValues
import org.apache.lucene.index.SortedDocValues
import org.apache.lucene.index.SortedSetDocValues
import org.apache.lucene.index.Term
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import org.apache.lucene.index.SlowCompositeReaderWrapper

object WrappedIndexReader {

  def apply(inner: DirectoryReader): WrappedIndexReader = {
    doOpen(inner, Map.empty[String, IdMapper])
  }

  def reopen(oldReader: WrappedIndexReader): WrappedIndexReader = {
    val oldInner = oldReader.inner
    val newInner = DirectoryReader.openIfChanged(oldInner)
    if (newInner != null) {
      var oldIdMappers = oldReader.wrappedSubReaders.foldLeft(Map.empty[String, IdMapper]){ (m, r) => m + (r.name -> r.getIdMapper) }
      doOpen(newInner, oldIdMappers)
    } else {
      oldReader
    }
  }

  private def doOpen(inner: DirectoryReader, oldIdMappers: Map[String, IdMapper]) = {
    val subReaders = inner.getContext.leaves.foldLeft(new ArrayBuffer[WrappedSubReader]){ (buf, cx) =>
      cx.reader match {
        case segmentReader: SegmentReader =>
          val segmentName = segmentReader.getSegmentName
          buf += new WrappedSubReader(segmentName, segmentReader, oldIdMappers.getOrElse(segmentName, ArrayIdMapper(segmentReader)))
        case subReader =>
          throw new IllegalStateException("not instance of %s but %s".format(classOf[SegmentReader].getName(), subReader.getClass.getName))
      }
    }
    new WrappedIndexReader(inner, subReaders.toArray)
  }
}

class WrappedIndexReader(val inner: DirectoryReader, val wrappedSubReaders: Array[WrappedSubReader])
extends MultiReader(wrappedSubReaders.map{ _.asInstanceOf[IndexReader] }.toArray, false) {

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
        while (i < wrappedSubReaders.length) {
          val r = wrappedSubReaders(i)
          val nextBase = base + r.maxDoc
          val liveDocs = r.getLiveDocs
          val docid = r.getIdMapper.getDocId(id)
          if (docid >= 0 && (liveDocs == null || liveDocs.get(docid))) return docid + base
          base = nextBase
          i += 1
        }
        -1
      }

      lazy private[this] val maxDocSum = wrappedSubReaders.map(_.maxDoc).sum
      def maxDoc():Int = maxDocSum
    }
  }

  private[this] lazy val _atomicReader: WrappedSubReader = new WrappedSubReader("", SlowCompositeReaderWrapper.wrap(this), getIdMapper)

  def asAtomicReader: WrappedSubReader = _atomicReader

  def add(indexReader: CachingIndexReader, idMapper: IdMapper) = {
    val remappers = wrappedSubReaders.foldLeft(Map.empty[String, DocIdRemapper]){ (m, r) => m + (r.name -> DocIdRemapper(idMapper, r.getIdMapper, r.inner)) }
    val splitReaders = indexReader.split(remappers)
    var newSubReaders = ArrayBuffer.empty[WrappedSubReader]
    wrappedSubReaders.foreach{ r =>
      newSubReaders += new WrappedSubReader(r.name, new PersonalizedIndexReader(r, splitReaders(r.name)), r.getIdMapper)
    }
    splitReaders.get("").foreach{ subReader =>
      newSubReaders += new WrappedSubReader("", subReader, idMapper)
    }

    new WrappedIndexReader(inner, newSubReaders.toArray)
  }
}

class WrappedSubReader(val name: String, val inner: AtomicReader, idMapper: IdMapper) extends AtomicReader {
  def getIdMapper = idMapper

  override def getNumericDocValues(field: String): NumericDocValues = inner.getNumericDocValues(field)
  override def getBinaryDocValues(field: String): BinaryDocValues = inner.getBinaryDocValues(field)
  override def getSortedDocValues(field: String): SortedDocValues = inner.getSortedDocValues(field)
  override def getSortedSetDocValues(field: String): SortedSetDocValues = inner.getSortedSetDocValues(field)
  override def getNormValues(field: String): NumericDocValues = inner.getNormValues(field)
  override def hasDeletions() = inner.hasDeletions()
  override def document(doc: Int, visitor: StoredFieldVisitor) = inner.document(doc, visitor)
  override def getFieldInfos() = inner.getFieldInfos()
  override def getTermVectors(doc: Int) = inner.getTermVectors(doc)
  override def maxDoc() = inner.maxDoc()
  override def numDocs() = inner.numDocs()
  override def fields() = inner.fields()
  override def getLiveDocs() = inner.getLiveDocs()
  protected def doClose() = {}
}
