package com.keepit.search.line

import org.apache.lucene.index.IndexReader

class BooleanNode[P <: LineQuery](required: Array[P], optional: Array[P], percentMatch: Float, reader: IndexReader) extends LineQuery(1.0f) {
  
  val weightOnRequiredNodes = required.foldLeft(0.0f){ (w, n) => w + n.weight }
  val threshold = if (percentMatch <= 0.0f) 0.0f else optional.foldLeft(weightOnRequiredNodes){ (w, n) => w + n.weight } * percentMatch / 100.0f
  
  override def fetchDoc(targetDoc: Int) = {
    if (curDoc < LineQuery.NO_MORE_DOCS) {
      curDoc = if (targetDoc <= curDoc) curDoc + 1 else targetDoc
    }
    
    var i = 0
    while (curDoc < LineQuery.NO_MORE_DOCS && i < required.length) {
      val node = required(i)
      
      if (node.curDoc < curDoc) node.fetchDoc(curDoc)
      if (node.curDoc == curDoc) {
        i += 1
        if (i == required.length) {
          // all required nodes matched, now bring optional nodes up and check matching percentage
          var runningWeight = weightOnRequiredNodes
          optional.foreach{ node => 
            if (node.curDoc < curDoc) node.fetchDoc(curDoc)
            if (node.curDoc == curDoc) runningWeight += node.weight
          }
          if (runningWeight <= threshold) {
            curDoc += 1 // try next doc
            i = 0
          }
        }
      }
      else {
        curDoc = node.curDoc
        i = 0
      }
    }
    curPos = -1
    curLine = -1
    curDoc
  }
  
  override def fetchLine(targetLine: Int) = {
    curLine = if (targetLine <= curLine) curLine + 1 else targetLine
    isScored = false
    
    var i = 0
    while (curLine < LineQuery.NO_MORE_LINES && i < required.length) {
      val node = required(i)
      
      if (node.curLine < curLine) node.fetchLine(curLine)
      if (node.curLine == curLine) {
        i += 1
        if (i == required.length) {
          // all required nodes matched, now bring optional nodes up and check matching percentage
          var runningWeight = weightOnRequiredNodes
          optional.foreach{ node => 
            if (node.curDoc == curDoc) {
              if (node.curLine < curLine) node.fetchLine(curLine)
              if (node.curLine == curLine) runningWeight += node.weight
            }
          }
          if (runningWeight < threshold) {
            curLine += 1 // try next line
            i = 0
          }
        }
      }
      else {
        curLine = node.curLine
        i = 0
      }
    }
    
    curPos = -1
    curLine
  }
  
  override def computeScore = {
    var sc = 0.0f
    required.foreach{ node => sc += node.score }
    optional.foreach{ node => if (node.curDoc == curDoc && node.curLine == curLine) sc += node.score }
    sc
  }
}
