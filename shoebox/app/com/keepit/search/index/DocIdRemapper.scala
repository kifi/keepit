package com.keepit.search.index

import org.apache.lucene.index.AtomicReader
import org.apache.lucene.util.Bits

object DocIdRemapper {
  def apply(srcMapper: IdMapper, dstIdMapper: IdMapper, indexReader: AtomicReader): DocIdRemapper = {
    indexReader.hasDeletions match {
      case true => new DocIdRemapperWithDeletionCheck(srcMapper, dstIdMapper, indexReader.getLiveDocs())
      case false => new DocIdRemapperNoDeletionCheck(srcMapper, dstIdMapper)
    }
  }
}

abstract class DocIdRemapper {
  def remap(docid: Int): Int
}

class DocIdRemapperWithDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper, liveDocs: Bits) extends DocIdRemapper {
  def remap(docid: Int): Int = {
    val newDocId = dstMapper.getDocId(srcMapper.getId(docid))
    if (newDocId < 0 || liveDocs.get(newDocId)) -1 else newDocId
  }
}

class DocIdRemapperNoDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper) extends DocIdRemapper {
  def remap(docid: Int) = {
    dstMapper.getDocId(srcMapper.getId(docid))
  }
}

