package com.keepit.search.engine.explain

import com.keepit.search.engine.{ Visibility, DirectScoreContext, ScoreExpr, ScoreContext }
import com.keepit.search.util.join.{ DataBuffer, DataBufferReader }
import java.util.Arrays

class ExplainContext(
    targetId: Long,
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    matchWeight: Array[Float],
    collector: ScoreDetailCollector) extends ScoreContext(scoreExpr, scoreArraySize, matchWeight, collector) {

  private[this] val scoreArray = new Array[Float](scoreArraySize)

  override def join(reader: DataBufferReader): Unit = {
    if (id == targetId) {
      val theVisibility = reader.recordType
      val id2 = if ((theVisibility & Visibility.HAS_SECONDARY_ID) != 0) reader.nextLong() else -1L

      Arrays.fill(scoreArray, 0.0f)

      while (reader.hasMore) {
        val bits = reader.nextTaggedFloatBits()
        val idx = DataBuffer.getTaggedFloatTag(bits)
        val scr = DataBuffer.getTaggedFloatValue(bits)
        scoreArray(idx) = scr
      }
      collector.collectDetail(id, id2, theVisibility, scoreArray.clone())

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
    collector: ScoreDetailCollector) extends DirectScoreContext(scoreExpr, scoreArray, matchWeight, collector) {

  def this(targetId: Long, scoreExpr: ScoreExpr, scoreArraySize: Int, matchWeight: Array[Float], collector: ScoreDetailCollector) = {
    this(targetId, scoreExpr, new Array[Float](scoreArraySize), matchWeight: Array[Float], collector: ScoreDetailCollector)
  }

  override def put(id: Long, visibility: Int): Unit = {
    if (id == targetId) {
      super.put(id, visibility) // do this before collectDetail to compute boost scores
      collector.collectDetail(id, -1, visibility, scoreArray)
    }
  }
}
