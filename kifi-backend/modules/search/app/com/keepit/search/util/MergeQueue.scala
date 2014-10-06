package com.keepit.search.util

import org.apache.lucene.util.PriorityQueue
import com.keepit.search.Scoring

object MergeQueue {
  class Hit[H](var score: Float, var scoring: Scoring, var hit: H)
}

class MergeQueue[H](sz: Int) extends PriorityQueue[MergeQueue.Hit[H]](sz) {
  import MergeQueue.Hit

  var highScore = Float.MinValue
  var totalHits = 0

  override def lessThan(a: Hit[H], b: Hit[H]) = (a.score < b.score)

  override def insertWithOverflow(hit: Hit[H]): Hit[H] = {
    totalHits += 1
    if (hit.score > highScore) highScore = hit.score
    super.insertWithOverflow(hit)
  }

  var overflow: Hit[H] = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def insert(score: Float, scoring: Scoring, hit: H) {
    if (overflow == null) {
      overflow = new Hit[H](score, scoring, hit)
    } else {
      overflow.score = score
      overflow.scoring = scoring
      overflow.hit = hit
      overflow
    }
    overflow = insertWithOverflow(overflow)
  }

  def insert(hit: Hit[H]) {
    overflow = insertWithOverflow(hit)
  }

  // the following method is destructive. after the call ArticleHitQueue is unusable
  def toSortedList: List[Hit[H]] = {
    var res: List[Hit[H]] = Nil
    var i = size()
    while (i > 0) {
      i -= 1
      res = pop() :: res
    }
    res
  }

  // the following method is destructive. after the call ArticleHitQueue is unusable
  def toRankedIterator = toSortedList.iterator.zipWithIndex

  def foreach(f: Hit[H] => Unit) {
    val arr = getHeapArray()
    val sz = size()
    var i = 1
    while (i <= sz) {
      f(arr(i).asInstanceOf[Hit[H]])
      i += 1
    }
  }

  def discharge(n: Int): List[Hit[H]] = {
    var i = 0
    var discharged: List[Hit[H]] = Nil
    while (i < n && size > 0) {
      discharged = pop() :: discharged
      i += 1
    }
    discharged
  }

  def reset() {
    super.clear()
    highScore = Float.MinValue
    totalHits = 0
  }
}
