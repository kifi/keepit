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
  def remap(srcDocId: Int): Int
  def maxDoc(): Int
  def numDocsRemapped(): Int = image.size
  val image: Set[Int]
}

class DocIdRemapperWithDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper, liveDocs: Bits) extends DocIdRemapper {
  def remap(srcDocId: Int): Int = {
    val dstDocId = dstMapper.getDocId(srcMapper.getId(srcDocId))
    if (dstDocId >= 0 && liveDocs.get(dstDocId)) dstDocId else -1
  }
  def maxDoc(): Int = dstMapper.maxDoc

  lazy val image: Set[Int] = {
    var im = Set.empty[Int]
    var docid = 0
    val srcMaxDoc = srcMapper.maxDoc
    while (docid < srcMaxDoc) {
      val dstDocId = dstMapper.getDocId(srcMapper.getId(docid))
      if (dstDocId >= 0 && liveDocs.get(dstDocId)) im += dstDocId
      docid += 1
    }
    im
  }
}

class DocIdRemapperNoDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper) extends DocIdRemapper {
  def remap(srcDocId: Int) = {
    dstMapper.getDocId(srcMapper.getId(srcDocId))
  }
  def maxDoc(): Int = dstMapper.maxDoc

  lazy val image: Set[Int] = {
    var im = Set.empty[Int]
    var docid = 0
    val srcMaxDoc = srcMapper.maxDoc
    while (docid < srcMaxDoc) {
      val dstDocId = dstMapper.getDocId(srcMapper.getId(docid))
      if (dstDocId >= 0) im += dstDocId
      docid += 1
    }
    im
  }
}

