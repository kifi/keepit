package com.keepit.search.index

class DocIdRemapper(srcMapper: IdMapper, dstMapper: IdMapper) {
  def src2dst(docid: Int) = {
    dstMapper.getDocId(srcMapper.getId(docid))
  }
  def dst2src(docid: Int) = {
    srcMapper.getDocId(dstMapper.getId(docid))
  }
}

