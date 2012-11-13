package com.keepit.search.line

import org.apache.lucene.index.IndexReader

class PhraseNode(termNodes: Array[TermNode], positions: Array[Int], weight: Float, reader: IndexReader) extends LineQuery(weight) {
  
  override def fetchDoc(targetDoc: Int) = {
    var doc = if (targetDoc <= curDoc && curDoc < LineQuery.NO_MORE_DOCS) curDoc + 1 else targetDoc
    
    var i = 0
    while (doc < LineQuery.NO_MORE_DOCS && i < termNodes.length) {
      val node = termNodes(i)
      
      if (node.curDoc < doc) node.fetchDoc(doc)
      if (node.curDoc == doc) {
        i += 1
      }
      else {
        doc = node.curDoc
        i = 0
      }
    }
    curPos = -1
    curLine = -1
    curDoc = doc
    curDoc
  }
  
  override private[line] def fetchPos() = {
    var pos = curPos + 1
    var i = 0
    while (pos < LineQuery.NO_MORE_POSITIONS && i < termNodes.length) {
      var node = termNodes(i)
      
      if (pos < LineQuery.NO_MORE_POSITIONS - positions(i)) { 
        val termPos = pos + positions(i)
        
        while (node.curPos < termPos) node.fetchPos()
        
        if (node.curPos > termPos) {
          if (node.curPos < LineQuery.NO_MORE_POSITIONS) {
            pos =  node.curPos - positions(i)
            i = 0
          } else {
            pos = LineQuery.NO_MORE_POSITIONS
          }
        } else {
          i += 1
          if (i == termNodes.length) {
            val limit = (pos / LineQuery.MAX_POSITION_PER_LINE) * LineQuery.MAX_POSITION_PER_LINE + LineQuery.MAX_POSITION_PER_LINE
            if (! termNodes.forall( _.curPos < limit )) {
              pos += 1
              i = 0
            }
          }
        }
      } else {
        pos = LineQuery.NO_MORE_POSITIONS
      }
    }
    curPos = pos
    curPos
  }
}
