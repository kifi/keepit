package com.keepit.search.query

import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue

object BooleanSubScorerQueue {
  def apply(scorers: Seq[BooleanScoreDoc]) = {
    val pq = new BooleanSubScorerQueue(scorers.size)
    scorers.foreach { pq.insertWithOverflow(_) }
    pq
  }
}

class BooleanSubScorerQueue(size: Int) extends PriorityQueue[BooleanScoreDoc](size) {
  override def lessThan(a: BooleanScoreDoc, b: BooleanScoreDoc) = ((a.doc < b.doc) || (a.doc == b.doc && a.state < b.state))
}

class BooleanScoreDoc(scorer: Scorer, val value: Float) {
  private[this] var _doc: Int = -1
  private[this] var _state: Int = 0

  def doc: Int = _doc

  def state: Int = _state

  def advance(target: Int) {
    _state = 0
    _doc = scorer.advance(target)
  }

  def next() {
    _state = 0
    _doc = scorer.nextDoc()
  }

  def prepare(): Float = {
    _state = 1
    value
  }

  def prepared: Boolean = (_state == 1)

  def scoreAndNext(): Float = {
    val sc = scorer.score()
    _state = 0
    _doc = scorer.nextDoc()
    sc
  }

  def cost(): Long = scorer.cost()
}

