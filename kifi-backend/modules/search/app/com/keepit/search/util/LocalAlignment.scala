package com.keepit.search.util

import AhoCorasick.State
import scala.math._
import java.util.Arrays

import LocalAlignment._
import com.keepit.common.logging.Logging

trait LocalAlignment {
  def begin(): Unit
  def end(): Unit
  def update(id: Int, position: Int, weight: Float = 1.0f): Unit
  def score: Float
  def maxScore: Float
}

object LocalAlignment {
  def apply(termIds: Array[Int], phraseMatcher: Option[PhraseMatcher], phraseBoost: Float, gapPenalty: Float): LocalAlignment = {
    val localAlignment = new BasicLocalAlignment(termIds, gapPenalty)
    phraseMatcher match {
      case Some(phraseMatcher) => new PhraseAwareLocalAlignment(phraseMatcher, phraseBoost, localAlignment)
      case _ => localAlignment
    }
  }

  trait Match {
    val pos: Int
    val len: Int
  }
  case class PhraseMatch(pos: Int, len: Int) extends Match
  case class TermMatch(pos: Int) extends Match { val len = 1 }

  class PhraseMatcher(dict: Seq[(Seq[Int], Match)]) extends AhoCorasick[Int, Match](dict)
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

  @inline private[this] def getGapPenalty(distance: Int): Float = gapPenalty * distance.toFloat

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

  def update(termId: Int, curPos: Int, weight: Float = 1.0f): Unit = {
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
      localScoreSum += ls(i) * weight
      i += 1
    }
    lastPos = curPos
    alignmentScore = max(alignmentScore, localScoreSum)
  }

  def end(): Unit = {}

  def score: Float = alignmentScore
}

class PhraseAwareLocalAlignment(phraseMatcher: PhraseMatcher, phraseBoost: Float, localAlignment: LocalAlignment, nonPhraseWeight: Float = 0.3f) extends LocalAlignment with Logging {
  private[this] val bufSize = phraseMatcher.maxLength
  private[this] var bufferedPos = -1
  private[this] var processedPos = -1
  private[this] var adjustment = 0
  private[this] var lastId = -1
  private[this] val ids = new Array[Int](bufSize)
  private[this] val matching = new Array[Boolean](bufSize)
  private[this] var state: State[Match] = phraseMatcher.initialState
  private[this] var matchedPhrases = Set.empty[PhraseMatch]
  private[this] var dictSize = phraseMatcher.size

  private[this] def flush(): Unit = {
    while (processedPos < bufferedPos) { processOnePosition() }
  }

  @inline private[this] def processOnePosition(): Unit = {
    processedPos += 1
    val weight = if (matching(processedPos % bufSize)) 1.0f else nonPhraseWeight
    localAlignment.update(ids(processedPos % bufSize), processedPos, weight)
  }

  def begin(): Unit = {
    bufferedPos = -1
    processedPos = -1
    adjustment = 0
    lastId = -1
    state = phraseMatcher.initialState
    matchedPhrases = Set.empty[PhraseMatch]
    localAlignment.begin()
  }

  def update(id: Int, rawPos: Int, weight: Float = 1.0f): Unit = { // weight is ignored
    if (rawPos == bufferedPos) {
      if (id == lastId) return // dedup
      adjustment += 1
    }
    lastId = id
    val pos = rawPos + adjustment
    if (pos - bufferedPos > 1) { // found a gap, flush buffer
      flush()
      processedPos = pos - 1
      state = phraseMatcher.initialState
    } else if (bufferedPos - processedPos >= bufSize) { // buffer full
      processOnePosition()
    }
    bufferedPos = pos
    ids(pos % bufSize) = id
    matching(pos % bufSize) = false

    state = phraseMatcher.next(id, state)
    state.check(pos, onMatch = {
      case (curPos, aMatch) =>
        var i = curPos - min(bufSize, aMatch.len)
        while (i < curPos) {
          i += 1
          if (i < 0) {
            log.error(s"i=$i curPos=$curPos aMatch.len=${aMatch.len}")
          } else {
            matching(i % bufSize) = true
          }
        }
        aMatch match {
          case phraseMatch: PhraseMatch => matchedPhrases += phraseMatch
          case _ => None
        }
    })
  }

  def end(): Unit = {
    flush() // flush remaining ids in the buffer
    localAlignment.end()
  }

  def score: Float = {
    localAlignment.score * ((1.0f - phraseBoost) + (matchedPhrases.size / dictSize) * phraseBoost)
  }

  def maxScore = localAlignment.maxScore
}
