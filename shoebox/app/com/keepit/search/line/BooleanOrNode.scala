package com.keepit.search.line

import org.apache.lucene.index.IndexReader

class BooleanOrNode[P <: LineQuery](optional: Array[P], percentMatch: Float, reader: IndexReader) extends LineQuery(1.0f) {
  
  val threshold = optional.foldLeft(0.0f){ (w, n) => w + n.weight } * percentMatch
 
  val pq = new NodeQueue(optional.length)
  optional.foreach{ node => pq.insertWithOverflow(node) }
  
  override def fetchDoc(targetDoc: Int) = {
    curDoc = if (targetDoc <= curDoc) curDoc + 1 else targetDoc
    
    var top = pq.top
    if (curDoc < LineQuery.NO_MORE_DOCS && pq.size > 0) {
      while (top.curDoc < curDoc) {
        top.fetchDoc(curDoc)
        top = pq.updateTop()
      }
      curDoc = top.curDoc
    } else {
      curDoc = LineQuery.NO_MORE_DOCS
    }
    curPos = -1
    curLine = -1
    curDoc
  }
  
  override def fetchLine(targetLine: Int) = {
    curLine = if (targetLine <= curLine) curLine + 1 else targetLine
    var runningScore = 0.0f
    
    if (curLine < LineQuery.NO_MORE_LINES) {
      var top = pq.top
      while (top.curDoc == curDoc && top.curLine < curLine) {
        top.fetchLine(curLine)
        top = pq.updateTop()
      }
      curLine = top.curLine // current min line
      
      var runningWeight = 0.0f
      while (curLine < LineQuery.NO_MORE_LINES && runningWeight < threshold) {
        curLine = top.curLine
        runningWeight = 0.0f
        runningScore = 0.0f
        if(curLine < LineQuery.NO_MORE_LINES) {
          while (top.curDoc == curDoc && top.curLine == curLine) {
            runningWeight += top.weight
            runningScore += top.score
            top.fetchLine(curLine)
            top = pq.updateTop()
          }
        }
      }
    }
    curScore = runningScore
    isScored = true
    curPos = -1
    curLine
  }
  
  override def computeScore = curScore
}
