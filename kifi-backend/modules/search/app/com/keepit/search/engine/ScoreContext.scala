package com.keepit.search.engine

import java.util.Arrays

import com.keepit.common.logging.Logging
import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBuffer, DataBufferReader, Joiner }

class ScoreContext(
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    matchWeight: Array[Float],
    collector: ResultCollector[ScoreContext]) extends Joiner {

  private[engine] var visibility: Int = 0

  private[engine] var secondaryId: Long = -1 // secondary id (keep id for kifi search)
  private[this] var secondaryIdScore: Float = -1.0f

  private[engine] var degree: Int = 0

  private[engine] val scoreMax = new Array[Float](scoreArraySize)
  private[engine] val scoreSum = new Array[Float](scoreArraySize)

  def score(): Float = scoreExpr()(this)

  def computeMatching(minThreshold: Float): Float = {
    val len = scoreMax.length
    var matching = 1.0f
    var i = 0
    while (i < len) { // using while for performance
      if (scoreMax(i) <= 0.0f) {
        matching -= matchWeight(i)
        if (matching < minThreshold) return 0.0f
      }
      i += 1
    }
    matching
  }

  def clear(): Unit = {
    visibility = Visibility.RESTRICTED
    secondaryId = -1L
    secondaryIdScore = -1.0f
    degree = 0
    Arrays.fill(scoreMax, 0.0f)
    Arrays.fill(scoreSum, 0.0f)
  }

  def join(reader: DataBufferReader): Unit = {
    val theVisibility = reader.recordType
    val id2 = if ((theVisibility & Visibility.HAS_SECONDARY_ID) != 0) reader.nextLong() else -1L
    var localSum = 0.0f // use a simple sum of scores to compare secondary ids

    while (reader.hasMore) {
      val bits = reader.nextTaggedFloatBits()
      val idx = DataBuffer.getTaggedFloatTag(bits)
      val scr = DataBuffer.getTaggedFloatValue(bits)
      localSum += scr
      scoreSum(idx) += scr
      if (scoreMax(idx) < scr) scoreMax(idx) = scr
    }

    if (id2 >= 0L && localSum > secondaryIdScore) {
      secondaryId = id2
      secondaryIdScore = localSum
    }

    visibility = visibility | theVisibility
    degree += 1
  }

  def flush(): Unit = {
    if (visibility != Visibility.RESTRICTED) collector.collect(this)
  }

  private[engine] def setVisibility(theVisibility: Int) = {
    visibility = theVisibility
  }

  private[engine] def addScore(idx: Int, scr: Float) = {
    scoreSum(idx) += scr
    if (scoreMax(idx) < scr) scoreMax(idx) = scr
  }
}

class ScoreContextWithDebug(
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    matchWeight: Array[Float],
    collector: ResultCollector[ScoreContext]) extends ScoreContext(scoreExpr, scoreArraySize, matchWeight, collector) with Logging with DebugOption {
  override def set(id: Long): Joiner = {
    if (debugTracedIds.contains(id)) debugLog(s"scorectx-set id=$id")
    super.set(id)
  }
  override def computeMatching(minThreshold: Float): Float = {
    val matching = super.computeMatching(minThreshold)
    if (debugTracedIds.contains(id)) debugLog(s"scorectx-matching id=${id} matching=${matching} weights=(${matchWeight.mkString(",")})")
    matching
  }
  override def score(): Float = {
    val scr = super.score()
    if (debugTracedIds.contains(id)) debugLog(s"scorectx-score id=${id} score=${scr}")
    scr
  }
  override def join(reader: DataBufferReader): Unit = {
    if (debugTracedIds.contains(id)) debugLog(s"scorectx-join id=${id} offset=${reader.recordOffset} recType=${reader.recordType}")
    super.join(reader)
  }
  override def flush(): Unit = {
    if (debugTracedIds.contains(id)) debugLog(s"scorectx-flush id=$id id2=$secondaryId deg=$degree vis=$visibility scoreMax=(${scoreMax.mkString(",")}) scoreSum=(${scoreSum.mkString(",")}})")
    super.flush()
  }
}

class DirectScoreContext(
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    matchWeight: Array[Float],
    collector: ResultCollector[ScoreContext]) extends ScoreContext(scoreExpr, scoreArraySize, matchWeight, collector) {

  private[this] var docId = -1
  private[this] var pq: TaggedScorerQueue = null

  def setScorerQueue(taggedScorerQueue: TaggedScorerQueue): Unit = {
    pq = taggedScorerQueue
  }

  override def score(): Float = {
    pq.addBoostScores(this, docId)
    scoreExpr()(this)
  }

  override private[engine] def addScore(idx: Int, scr: Float) = {
    // there shouldn't be any duplicate index in the direct path
    scoreMax(idx) = scr
    scoreSum(idx) = scr
  }

  override def flush(): Unit = {
    if (visibility != Visibility.RESTRICTED) {
      degree = 1
      docId = pq.addCoreScores(this)
      collector.collect(this)
      docId = -1
    }
  }

  override def join(reader: DataBufferReader): Unit = throw new UnsupportedOperationException("DirectScoreContext does not support join")
}
