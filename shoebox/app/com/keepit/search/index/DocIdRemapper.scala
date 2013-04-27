package com.keepit.search.index

import org.apache.lucene.index.AtomicReader
import org.apache.lucene.util.Bits

object DocIdRemapper {
  def apply(srcMapper: IdMapper, dstIdMapper: IdMapper, indexReader: AtomicReader): DocIdRemapper = {
    indexReader.getLiveDocs match {
      case null => new DocIdRemapperNoDeletionCheck(srcMapper, dstIdMapper)
      case liveDocs => new DocIdRemapperWithDeletionCheck(srcMapper, dstIdMapper, liveDocs)
    }
  }
}

abstract class DocIdRemapper {
  def remap(docid: Int): Int
  def maxDoc(): Int
  def numDocsRemapped(): Int
}

class DocIdRemapperWithDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper, liveDocs: Bits) extends DocIdRemapper {
  def remap(docid: Int): Int = {
    val newDocId = dstMapper.getDocId(srcMapper.getId(docid))
    if (newDocId >= 0 && liveDocs.get(newDocId)) newDocId else -1
  }
  def maxDoc(): Int = dstMapper.maxDoc
  def numDocsRemapped(): Int = {
    var numRemapped = 0
    var docid = 0
    val srcMaxDoc = srcMapper.maxDoc
    while (docid < srcMaxDoc) {
      val newDocId = dstMapper.getDocId(srcMapper.getId(docid))
      if (newDocId >= 0 && liveDocs.get(newDocId)) numRemapped += 1
      docid += 1
    }
    numRemapped
  }
}

class DocIdRemapperNoDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper) extends DocIdRemapper {
  def remap(docid: Int) = {
    dstMapper.getDocId(srcMapper.getId(docid))
  }
  def maxDoc(): Int = dstMapper.maxDoc
  def numDocsRemapped(): Int = {
    var numRemapped = 0
    var docid = 0
    val srcMaxDoc = srcMapper.maxDoc
    while (docid < srcMaxDoc) {
      if (dstMapper.getDocId(srcMapper.getId(docid)) >= 0) numRemapped += 1
      docid += 1
    }
    numRemapped
  }
}

