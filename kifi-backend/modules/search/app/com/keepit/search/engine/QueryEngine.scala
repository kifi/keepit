package com.keepit.search.engine

import com.keepit.common.logging.Logging
import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBufferReader, JoinerManager, DataBuffer, HashJoin }
import org.apache.lucene.search.{ Query, Weight }

import scala.collection.mutable.ListBuffer

class QueryEngine private[engine] (scoreExpr: ScoreExpr, query: Query, totalSize: Int, coreSize: Int) extends Logging with DebugOption {

  private[this] val dataBuffer: DataBuffer = new DataBuffer()
  private[this] val matchWeights: Array[Float] = new Array[Float](totalSize)

  private[this] def accumulateWeightInfo(weights: IndexedSeq[(Weight, Float)]): Unit = {
    var i = 0
    while (i < totalSize) {
      matchWeights(i) += weights(i)._2
      i += 1
    }
  }
  private def normalizeMatchWeight(): Unit = {
    var sum = 0.0f
    var i = 0
    while (i < totalSize) {
      sum += matchWeights(i)
      i += 1
    }
    if (sum != 0.0f) {
      i = 0
      while (i < totalSize) {
        matchWeights(i) = matchWeights(i) / sum
        i += 1
      }
    }
  }

  def execute(collector: ResultCollector[ScoreContext], sources: ScoreVectorSource*): Unit = {

    val directScoreContext = new ScoreContext(scoreExpr, totalSize, matchWeights, collector)

    sources.foldLeft(0) { (total, source) =>
      val startTime = System.currentTimeMillis()

      val newTotal = execute(source, directScoreContext)

      val elapsed = System.currentTimeMillis() - startTime
      if ((debugFlags & DebugOption.Log.flag) != 0) {
        debugLog(s"source executed: class=${source.getClass.getName} rows=${newTotal - total} time=$elapsed")
      }
      newTotal
    }

    val startTime = System.currentTimeMillis()

    join(collector)

    val elapsed = System.currentTimeMillis() - startTime

    debugLog(s"engine executed: bufSize=${dataBuffer.numPages * DataBuffer.PAGE_SIZE} joinTime=$elapsed")
  }

  private def execute(source: ScoreVectorSource, directScoreContext: ScoreContext): Int = {
    // if NullExpr, no need to execute
    if (scoreExpr.isNullExpr) return dataBuffer.size

    val weights = source.createWeights(query)
    if (weights.nonEmpty) {
      // extract and accumulate information from Weights for later use (percent match)
      accumulateWeightInfo(weights)

      source.execute(weights, coreSize, dataBuffer, directScoreContext)
    } else {
      log.error("no weight created")
    }
    dataBuffer.size
  }

  private def join(collector: ResultCollector[ScoreContext]): Unit = {
    if (debugTracedIds != null) dumpBuf(debugTracedIds)

    val size = dataBuffer.size
    if (size > 0) {
      normalizeMatchWeight()

      val hashJoin = new HashJoin(dataBuffer, (size + 10) / 10, createJoinerManager(collector))
      hashJoin.execute()
    }
  }

  def getScoreExpr(): ScoreExpr = scoreExpr
  def getQuery(): Query = query
  def getTotalSize(): Int = totalSize
  def getCoreSize(): Int = coreSize
  def getMatchWeights(): Array[Float] = matchWeights

  private def createJoinerManager(collector: ResultCollector[ScoreContext]): JoinerManager = {
    if (debugTracedIds == null) {
      new JoinerManager(32) {
        def create() = new ScoreContext(scoreExpr, totalSize, matchWeights, collector)
      }
    } else {
      new JoinerManager(32) {
        def create() = new ScoreContext(scoreExpr, totalSize, matchWeights, collector) {
          override def set(id: Long) = {
            if (debugTracedIds.contains(id)) debugLog(s"joiner-set id=$id")
            super.set(id)
          }
          override def join(reader: DataBufferReader) = {
            if (debugTracedIds.contains(id)) debugLog(s"joiner-join id=${id} offset=${reader.recordOffset} recType=${reader.recordType}")
            super.join(reader)
          }
          override def flush() = {
            if (debugTracedIds.contains(id)) debugLog(s"joiner-flush id=$id")
            super.flush()
          }
        }
      }
    }
  }

  private def dumpBuf(ids: Set[Long]): Unit = {
    dataBuffer.scan(new DataBufferReader) { reader =>
      // assuming the first datum is ID
      val id = reader.nextLong()
      if (ids.contains(id)) {
        val visibility = reader.recordType
        val id2 = if ((visibility & Visibility.HAS_SECONDARY_ID) != 0) reader.nextLong() else -1L
        def scores: String = {
          val out = new ListBuffer[(Int, Float)]
          while (reader.hasMore()) {
            val bits = reader.nextTaggedFloatBits()
            val idx = DataBuffer.getTaggedFloatTag(bits)
            val scr = DataBuffer.getTaggedFloatValue(bits)
            out += ((idx, scr))
          }
          out.mkString("[", ", ", "]")
        }
        debugLog(s"databuf id=$id id2=$id2 recType=${reader.recordType} scores=${scores}")
      }
    }
  }
}
