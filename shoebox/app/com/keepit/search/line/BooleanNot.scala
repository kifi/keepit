package com.keepit.search.line

import org.apache.lucene.index.IndexReader

class BooleanNot[P <: LineQuery](source: P, prohibited: Array[P], reader: IndexReader) extends LineQuery(1.0f) {
  
  override def fetchDoc(targetDoc: Int) = {
    val doc = source.fetchDoc(targetDoc)
    
    // NOTE: we don't know if this is a hit or not at this point 
    prohibited.foreach{ node => if (node.curDoc < doc) node.fetchDoc(doc) }

    curPos = -1
    curLine = -1
    curDoc = doc
    curDoc
  }
  
  private def isProhibited(line: Int) = prohibited.exists{ n =>
    if (n.curDoc == curDoc) {
      if (n.curLine < line) n.fetchLine(line)
      n.curLine == line
    } else false
  }
  
  override def fetchLine(targetLine: Int) = {
    var line = source.fetchLine(targetLine)
    while (line < LineQuery.NO_MORE_LINES && isProhibited(line)) {
      line = source.fetchLine(targetLine)
    }
    curPos = -1
    curLine = line
    curLine
  }
  
  override def computeScore = source.score
}
