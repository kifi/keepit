package com.keepit.search.index

import org.apache.lucene.index.IndexReader

object DocIdRemapper {
  def apply(srcMapper: IdMapper, dstIdMapper: IdMapper, indexReader: IndexReader): DocIdRemapper = {
    indexReader.hasDeletions match {
      case true => new DocIdRemapperWithDeletionCheck(srcMapper, dstIdMapper, indexReader)
      case false => new DocIdRemapperNoDeletionCheck(srcMapper, dstIdMapper)
    }
  }
}

abstract class DocIdRemapper {
  def src2dst(docid: Int): Int
  def dst2src(docid: Int): Int
}

class DocIdRemapperWithDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper, indexReader: IndexReader) extends DocIdRemapper {
  def src2dst(docid: Int): Int = {
    val newDocId = dstMapper.getDocId(srcMapper.getId(docid))
    if (newDocId < 0 || indexReader.isDeleted(newDocId)) -1 else newDocId
   }
  def dst2src(docid: Int) = {
    val newDocId = srcMapper.getDocId(dstMapper.getId(docid))
    if (newDocId < 0 || indexReader.isDeleted(newDocId)) -1 else newDocId
  }
}

class DocIdRemapperNoDeletionCheck(srcMapper: IdMapper, dstMapper: IdMapper) extends DocIdRemapper {
  def src2dst(docid: Int) = {
    dstMapper.getDocId(srcMapper.getId(docid))
  }
  def dst2src(docid: Int) = {
    srcMapper.getDocId(dstMapper.getId(docid))
  }
}

