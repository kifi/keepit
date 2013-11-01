package com.keepit.search

import com.keepit.common.logging.Logging
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.Bits

class SemanticVectorEnum(inner: DocsAndPositionsEnum) extends DocIdSetIterator with Logging {

  override def docID(): Int = inner.docID()

  override def nextDoc(): Int = inner.nextDoc()

  override def advance(target: Int): Int = inner.advance(target)

  def getSemanticVector(): BytesRef = {
    if (inner.freq() > 0) {
      inner.nextPosition()
      val payload = inner.getPayload()
      if (payload != null) {
        if (payload.length == SemanticVector.arraySize) {
          return payload
        } else {
          log.error(s"wrong payload size: ${payload.length}")
        }
      } else {
        log.error("no payload")
      }
    } else {
      log.error("no payload")
    }
    null
  }
}

