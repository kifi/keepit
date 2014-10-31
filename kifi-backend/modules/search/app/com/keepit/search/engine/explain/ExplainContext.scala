package com.keepit.search.engine.explain

import com.keepit.common.logging.Logging
import com.keepit.search.engine._
import com.keepit.search.util.join.{ AggregationContext, DataBuffer, DataBufferReader }
import java.util.Arrays

class ExplainContext(
    targetId: Long,
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    matchWeight: Array[Float],
    collector: ScoreDetailCollector) extends ScoreContext(scoreExpr, scoreArraySize, matchWeight, collector) with Logging with DebugOption {

  private[this] val scoreArray = new Array[Float](scoreArraySize)

  override def set(id: Long): AggregationContext = {
    if (debugTracedIds.contains(id)) debugLog(s"explainctx-set id=$id")
    super.set(id)
  }

  override def computeMatching(minThreshold: Float): Float = {
    val matching = super.computeMatching(minThreshold)
    if (debugTracedIds.contains(id)) debugLog(s"explainctx-matching id=${id} matching=${matching} weights=[${matchWeight.mkString(",")}]")
    matching
  }

  override def score(): Float = {
    val scr = super.score()
    if (debugTracedIds.contains(id)) debugLog(s"explainctx-score id=${id} score=${scr}")
    scr
  }

  override def flush(): Unit = {
    if (debugTracedIds.contains(id)) debugLog(s"explainctx-flush id=$id id2=$secondaryId deg=$degree visibility=[${Visibility.toString(visibility)}] scoreMax=(${scoreMax.mkString(",")}) scoreSum=(${scoreSum.mkString(",")})")
    super.flush()
  }

  override def join(reader: DataBufferReader): Unit = {
    if (id == targetId) {
      if (debugTracedIds.contains(id)) debugLog(s"explainctx-join id=${id} visibility=[${Visibility.toString(reader.recordType)}] offset=${reader.recordOffset}")

      val theVisibility = reader.recordType
      val id2 = if ((theVisibility & Visibility.HAS_SECONDARY_ID) != 0) reader.nextLong() else -1L

      Arrays.fill(scoreArray, 0.0f)

      while (reader.hasMore) {
        val bits = reader.nextTaggedFloatBits()
        val idx = DataBuffer.getTaggedFloatTag(bits)
        val scr = DataBuffer.getTaggedFloatValue(bits)
        scoreArray(idx) = scr
      }
      collector.collectDetail(id, id2, theVisibility, scoreArray)

      reader.rewind()
      reader.nextLong() // discard the Id
      super.join(reader)
    }
  }
}

class DirectExplainContext(
    targetId: Long,
    scoreExpr: ScoreExpr,
    scoreArray: Array[Float],
    matchWeight: Array[Float],
    collector: ScoreDetailCollector) extends DirectScoreContext(scoreExpr, scoreArray, matchWeight, collector) with Logging with DebugOption {

  def this(targetId: Long, scoreExpr: ScoreExpr, scoreArraySize: Int, matchWeight: Array[Float], collector: ScoreDetailCollector) = {
    this(targetId, scoreExpr, new Array[Float](scoreArraySize), matchWeight: Array[Float], collector: ScoreDetailCollector)
  }

  override def put(id: Long, visibility: Int): Unit = {
    if (id == targetId) {
      if (debugTracedIds.contains(id)) debugLog(s"explainctx-put id=$id visibility=[${Visibility.toString(visibility)}]")
      super.put(id, visibility) // do this before collectDetail to compute boost scores
      collector.collectDetail(id, -1, visibility, scoreArray)
    }
  }
}
