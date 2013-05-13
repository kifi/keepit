package com.keepit.search.index

import org.apache.lucene.index.AtomicReader
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import com.keepit.search.util.ReverseArrayMapper

abstract class IdMapper {
  def getId(docid: Int): Long
  def getDocId(id: Long): Int
  def maxDoc(): Int
}

object ArrayIdMapper {
  def apply(indexReader: AtomicReader) = {
    val maxDoc = indexReader.maxDoc()
    val liveDocs = indexReader.getLiveDocs()
    val idArray = new Array[Long](maxDoc)
    val idVals = indexReader.getNumericDocValues(Indexer.idValueFieldName)
    var idx = 0
    if (idVals != null) {
      while (idx < maxDoc) {
        idArray(idx) = if (liveDocs == null || liveDocs.get(idx)) idVals.get(idx) else Indexer.DELETED_ID
        idx += 1
      }
    } else {
      while (idx < maxDoc) {
        idArray(idx) = Indexer.DELETED_ID
        idx += 1
      }
    }
    new ArrayIdMapper(idArray)
  }
}

class ArrayIdMapper(idArray: Array[Long]) extends IdMapper {
  val reserveMapper = ReverseArrayMapper(idArray, 0.9d, Indexer.DELETED_ID)

  def getId(docid: Int) = idArray(docid) // no range check done for performance
  def getDocId(id: Long) = reserveMapper(id)
  def maxDoc(): Int = idArray.length
}
