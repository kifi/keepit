package com.keepit.search.line

import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term

class TermNode(term: Term, initialWeight: Float, reader: IndexReader) extends LineQuery(initialWeight) {
  val tp = reader.termPositions(term)
  var posLeft = 0

  override def fetchDoc(targetDoc: Int) = {
    if (curDoc < LineQuery.NO_MORE_DOCS) {
      curDoc = if (targetDoc <= curDoc) curDoc + 1 else targetDoc
    }

    if (curDoc < LineQuery.NO_MORE_DOCS && tp.skipTo(curDoc)) {
      curDoc = tp.doc()
      posLeft = tp.freq()
    } else {
      tp.close()
      curDoc = LineQuery.NO_MORE_DOCS
      posLeft = 0
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
