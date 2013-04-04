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
}

class DocIdRemapperWithDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper, liveDocs: Bits) extends DocIdRemapper {
  def remap(docid: Int): Int = {
    val newDocId = dstMapper.getDocId(srcMapper.getId(docid))
    if (newDocId >= 0 && liveDocs.get(newDocId)) newDocId else -1
  }
}

class DocIdRemapperNoDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper) extends DocIdRemapper {
  def remap(docid: Int) = {
    dstMapper.getDocId(srcMapper.getId(docid))
  }
}

