package com.keepit.search.line

import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._

object LineQuery  {
  val MAX_POSITION_PER_LINE = 2048
  val LINE_GAP = 3
  val NO_MORE_POSITIONS = Integer.MAX_VALUE
  val NO_MORE_LINES = NO_MORE_POSITIONS/MAX_POSITION_PER_LINE
  val NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS
  
  val emptyQueryNode = new LineQuery(0) {
    override def fetch(targetDoc: Int) = NO_MORE_DOCS
    override def fetchDoc(targetDoc: Int) = NO_MORE_DOCS
    override def fetchLine(targetDoc: Int) = NO_MORE_LINES
    override def score = 0.0f
  }
}

abstract class LineQuery(val weight: Float) {
  
  private[line] var curDoc = -1
  private[line] var curLine = -1
  private[line] var curPos = -1
  private[line] var curScore = 0.0f
  private[line] var isScored = false
  
  def fetch(targetDoc: Int) = {
    var done = false
    while (!done) {
      if (fetchDoc(targetDoc) < LineQuery.NO_MORE_DOCS){ 
        if (fetchLine(0) < LineQuery.NO_MORE_LINES) {
          done = true
        }
      } else {
        done = true
      }
    }
    curDoc
  }

  def fetchDoc(targetDoc: Int): Int
  
  def fetchLine(targetLine: Int) = {
    val line = if (targetLine <= curLine) curLine + 1 else targetLine
    isScored = false
    
    if (curPos >= 0) curLine = curPos / LineQuery.MAX_POSITION_PER_LINE
    while (curLine < line && curLine < LineQuery.NO_MORE_LINES) {
      fetchPos()
      curLine = curPos / LineQuery.MAX_POSITION_PER_LINE
    }
    curLine
  }
  
  def score = {
    if (!isScored) {
      curScore = computeScore
      isScored = true
    }
    curScore
  }
  
  def computeScore = {
    var freq = 0
    var sc = 0.0f
    if (curLine < LineQuery.NO_MORE_LINES) {
      while (curPos / LineQuery.MAX_POSITION_PER_LINE == curLine) {
        freq += 1
        fetchPos()
      }
      sc = weight * freq
    }
    sc
  }
  
  private[line] def fetchPos() = LineQuery.NO_MORE_POSITIONS
}

class NodeQueue(size: Int) extends PriorityQueue[LineQuery] {
  super.initialize(size);
  
  override def lessThan(nodeA: LineQuery, nodeB: LineQuery) = {
    if (nodeA.curDoc == nodeB.curDoc) (nodeA.curLine < nodeB.curLine)
    else (nodeA.curDoc < nodeB.curDoc)
  }
}