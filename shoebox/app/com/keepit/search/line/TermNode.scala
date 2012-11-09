package com.keepit.search.line

import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term

class TermNode(term: Term, weight: Float, reader: IndexReader) extends LineQuery(weight) {
  val tp = reader.termPositions(term)
  var posLeft = 0
  
  override def fetchDoc(targetDoc: Int) = {
    val doc = if (targetDoc <= curDoc) curDoc + 1 else targetDoc
    
    if (tp.skipTo(doc)) {
      curDoc = tp.doc()
      posLeft = tp.freq()
      curLine = -1
      curPos = -1
    } else {
      curDoc = LineQuery.NO_MORE_DOCS;
      tp.close()
    }
    curPos = -1
    curLine = -1
    curDoc
  }
  
  override private[line] def fetchPos() = {
    if (posLeft > 0) {
      posLeft -= 1
      curPos = tp.nextPosition()
    } else {
      curPos = LineQuery.NO_MORE_POSITIONS
    }
    curPos
  }
}