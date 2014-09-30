package com.keepit.search.engine

import com.keepit.search.util.join.DataBuffer.FloatTagger
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer

final class TaggedScorer(tag: Int, scorer: Scorer) extends FloatTagger(tag) {
  def doc = scorer.docID()
  def next = scorer.nextDoc()
  def advance(docId: Int) = scorer.advance(docId)
  def taggedScore() = tagFloat(scorer.score)
  def taggedScore(boost: Float) = tagFloat(scorer.score * boost)
  def addScore(scoreContext: ScoreContext): Unit = scoreContext.addScore(tag, scorer.score)
}

object TaggedScorerQueue {
  def apply(scorers: Array[Scorer], coreSize: Int): TaggedScorerQueue = {
    val boosterScorers: ArrayBuffer[TaggedScorer] = ArrayBuffer()
    var i = coreSize
    while (i < scorers.length) {
      val sc = scorers(i)
      if (sc != null && sc.nextDoc() < NO_MORE_DOCS) {
        boosterScorers.+=(new TaggedScorer(i, sc))
      }
      i += 1
    }

    val pq = new TaggedScorerQueue(coreSize, boosterScorers.toArray)
    i = 0
    while (i < coreSize) {
      val sc = scorers(i)
      if (sc != null && sc.nextDoc() < NO_MORE_DOCS) {
        pq.insertWithOverflow(new TaggedScorer(i, sc))
      }
      i += 1
    }
    pq
  }
}

final class TaggedScorerQueue(coreSize: Int, boosterScorers: Array[TaggedScorer]) extends PriorityQueue[TaggedScorer](coreSize) {
  override def lessThan(a: TaggedScorer, b: TaggedScorer): Boolean = (a.doc < b.doc)

  def getTaggedScores(taggedScores: Array[Int], boost: Float = 1.0f): Int = {
    var scorer = top()
    val docId = scorer.doc
    var size: Int = 0
    while (scorer.doc == docId) {
      taggedScores(size) = scorer.taggedScore(boost)
      size += 1
      scorer.next
      scorer = updateTop()
    }

    if (size > 0) {
      var i = 0
      val len = boosterScorers.length
      while (i < len) {
        val scorer = boosterScorers(i)
        if (scorer.doc < docId) {
          if (scorer.advance(docId) == docId) {
            taggedScores(size) = scorer.taggedScore()
            size += 1
          }
        } else if (scorer.doc == docId) {
          taggedScores(size) = scorer.taggedScore()
          size += 1
        }
        i += 1
      }
    }

    size
  }

  def addCoreScores(scoreContext: ScoreContext): Int = {
    var scorer = top()
    val docId = scorer.doc
    var size: Int = 0
    while (scorer.doc == docId) {
      scorer.addScore(scoreContext)
      size += 1
      scorer.next
      scorer = updateTop()
    }
    if (size > 0) docId else -1
  }

  def addBoostScores(scoreContext: ScoreContext, docId: Int): Unit = {
    var i = 0
    val len = boosterScorers.size
    while (i < len) {
      val scorer = boosterScorers(i)
      if (scorer.doc < docId) {
        if (scorer.advance(docId) == docId) {
          scorer.addScore(scoreContext)
        }
      } else if (scorer.doc == docId) {
        scorer.addScore(scoreContext)
      }
      i += 1
    }
  }

  def skipCurrentDoc(): Int = {
    var scorer = top()
    val docId = scorer.doc
    while (scorer.doc <= docId) {
      scorer.next
      scorer = updateTop()
    }
    scorer.doc
  }

  def createScoreArray: Array[Int] = new Array[Int](size() + boosterScorers.length)
}
