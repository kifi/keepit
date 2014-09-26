package com.keepit.search.engine

import com.keepit.search.util.join.DataBuffer
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer

final class TaggedScorer(tag: Int, scorer: Scorer) extends DataBuffer.FloatTagger(tag) {
  def doc = scorer.docID()
  def next = scorer.nextDoc()
  def advance(docId: Int) = scorer.advance(docId)
  def taggedScore() = tagFloat(scorer.score)
  def taggedScore(boost: Float) = tagFloat(scorer.score * boost)
  def addScore(scoreContext: ScoreContext): Unit = scoreContext.addScore(tag, scorer.score)
}

final class TaggedScorerQueue(coreSize: Int) extends PriorityQueue[TaggedScorer](coreSize) {
  override def lessThan(a: TaggedScorer, b: TaggedScorer): Boolean = (a.doc < b.doc)

  private val dependentScores: ArrayBuffer[TaggedScorer] = ArrayBuffer()

  def addDependentScorer(scorer: TaggedScorer): Unit = { dependentScores += scorer }

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
      val len = dependentScores.size
      while (i < len) {
        val scorer = dependentScores(i)
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

  def addScores(scoreContext: ScoreContext): Unit = {
    var scorer = top()
    val docId = scorer.doc
    var size: Int = 0
    while (scorer.doc == docId) {
      scorer.addScore(scoreContext)
      size += 1
      scorer.next
      scorer = updateTop()
    }

    if (size > 0) {
      var i = 0
      val len = dependentScores.size
      while (i < len) {
        val scorer = dependentScores(i)
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

  def createScoreArray: Array[Int] = new Array[Int](size() + dependentScores.size)
}
