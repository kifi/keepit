package com.keepit.search.engine.result

import org.apache.lucene.util.PriorityQueue

class HitQueue(sz: Int) extends PriorityQueue[Hit](sz) {

  private[this] var _highScore = Float.MinValue
  def highScore: Float = _highScore

  private[this] var _totalHits = 0
  def totalHits: Int = 0

  override def lessThan(a: Hit, b: Hit) = (a.normalizedScore < b.normalizedScore)

  override def insertWithOverflow(hit: Hit): Hit = {
    _totalHits += 1
    if (hit.score > _highScore) _highScore = hit.score
    super.insertWithOverflow(hit)
  }

  private[this] var overflow: Hit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def insert(id: Long, score: Float, visibility: Int, secondaryId: Long): Unit = {
    if (overflow == null) overflow = new Hit()

    overflow.id = id
    overflow.score = score
    overflow.normalizedScore = score
    overflow.visibility = visibility
    overflow.secondaryId = secondaryId

    overflow = insertWithOverflow(overflow)
  }

  def insert(hit: Hit): Unit = {
    overflow = insertWithOverflow(hit)
  }

  // the following method is destructive. after the call ArticleHitQueue is unusable
  def toSortedList: List[Hit] = {
    var res: List[Hit] = Nil
    var i = size()
    while (i > 0) {
      i -= 1
      res = pop() :: res
    }
    res
  }

  // the following method is destructive. after the call ArticleHitQueue is unusable
  def toRankedIterator: Iterator[(Hit, Int)] = toSortedList.iterator.zipWithIndex

  def foreach(f: Hit => Unit): Unit = {
    val arr = getHeapArray()
    val sz = size()
    var i = 1
    while (i <= sz) {
      f(arr(i).asInstanceOf[Hit])
      i += 1
    }
  }

  def discharge(n: Int): List[Hit] = {
    var i = 0
    var discharged: List[Hit] = Nil
    while (i < n && size > 0) {
      discharged = pop() :: discharged
      i += 1
    }
    discharged
  }

  def reset() {
    super.clear()
    _highScore = Float.MinValue
    _totalHits = 0
  }
}
