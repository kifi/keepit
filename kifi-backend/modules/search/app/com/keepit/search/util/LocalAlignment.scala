package com.keepit.search.util

import AhoCorasick.State
import scala.math._

import java.util.Arrays

trait LocalAlignment {
  def begin(): Unit
  def end(): Unit
  def update(id: Int, position: Int): Unit
  def score: Float
  def maxScore: Float
}

object LocalAlignment {
  def apply(termIds: Array[Int], ac: Option[AhoCorasick[Int, Int]], gapPenalty: Float): LocalAlignment = {
    val localAlignment = new BasicLocalAlignment(termIds, gapPenalty)
    ac match {
      case Some(ac) => new PhraseAwareLocalAlignment(ac, localAlignment)
      case _ => localAlignment
    }
  }
}

class BasicLocalAlignment(termIds: Array[Int], gapPenalty: Float) extends LocalAlignment {
  // Find all partial sequence matches (possible phrases) using the dynamic programming.
  // This is similar to a local alignment matching in bioinformatics, but we disallow gaps.
  // We give a weight to each term occurrence using a local alignment score, thus, a term in a
  // a matching sequence gets a high weight. A weight is diffused to following positions with a constant decay.
  // A local score at a position is the sum of weights of all terms.
  // The max local score + the base score is the score of the current document.
  private[this] val numTerms = termIds.length
  private[this] val rl = new Array[Float](numTerms) // run lengths
  private[this] val ls = new Array[Float](numTerms) // local scores
  private[this] var alignmentScore = 0.0f
  private[this] var lastPos = -1
  private[this] var dist = 1

  private[this] def getGapPenalty(distance: Int): Float = gapPenalty * distance.toFloat

  def maxScore: Float = {
     // max possible local alignment score
    val n = termIds.length.toFloat
    ((n * (n + 1.0f) / 2.0f) - (gapPenalty * n * (n - 1.0f) / 2.0f))
  }

  def begin(): Unit = {
    Arrays.fill(rl, 0.0f) // clear the run lengths
    Arrays.fill(ls, 0.0f) // clear the local scores
    alignmentScore = 0.0f
    lastPos = -1
  }

  def update(termId: Int, curPos: Int): Unit = {
    if (lastPos < curPos) dist = curPos - lastPos
    // update run lengths and local scores
    var prevRun = 0.0f
    var localScoreSum = 0.0f
    var i = 0
    while (i < numTerms) {
      val runLen = if (termIds(i) == termId) prevRun + 1.0f else 0.0f
      val localScore = max(ls(i) - (getGapPenalty(dist)), 0.0f)
      prevRun = rl(i) // store the run length of previous round
      rl(i) = runLen
      ls(i) = if (localScore < runLen) runLen else localScore
      localScoreSum += ls(i)
      i += 1
    }
    lastPos = curPos
    alignmentScore = max(alignmentScore, localScoreSum)
  }

  def end(): Unit = {}

  def score: Float = alignmentScore
}

class PhraseAwareLocalAlignment(ac: AhoCorasick[Int, Int], localAlignment: LocalAlignment) extends LocalAlignment {
  private[this] val bufSize = ac.maxLength
  private[this] var bufferedPos = -1
  private[this] var processedPos = -1
  private[this] val ids = new Array[Int](bufSize)
  private[this] val matching = new Array[Boolean](bufSize)
  private[this] var state: State[Int] = ac.root

  private def flush(): Unit = {
    while (processedPos < bufferedPos) { processOnePosition() }
  }

  private def processOnePosition(): Unit = {
    processedPos += 1
    if (matching(processedPos % bufSize)) localAlignment.update(ids(processedPos % bufSize), processedPos)
  }

  def begin(): Unit = {
    bufferedPos = -1
    processedPos = -1
    state = ac.root
    localAlignment.begin()
  }

  def update(id: Int, pos: Int): Unit = {
    if (pos - bufferedPos > 1) { // found a gap, flush buffer
      flush()
      processedPos = pos - 1
      state = ac.root
    } else if (bufferedPos - processedPos >= bufSize) { // buffer full
      processOnePosition()
    }
    bufferedPos = pos
    ids(pos % bufSize) = id
    matching(pos % bufSize) = false

    state = ac.next(id, state)
    state.check(pos, onMatch = { (curPos, len) =>
      var i = curPos - min(bufSize, len)
      while (i < curPos) {
        i += 1
        matching(i % bufSize) = true
      }
    })
  }

  def end(): Unit = {
    flush() // flush remaining ids in the buffer
    localAlignment.end()
  }

  def score: Float = localAlignment.score
  def maxScore = localAlignment.maxScore
}

