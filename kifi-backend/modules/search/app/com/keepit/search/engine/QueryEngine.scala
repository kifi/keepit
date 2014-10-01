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
  private[this] def normalizeMatchWeight(): Unit = {
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

    sources.foreach { source =>
      val startTime = System.currentTimeMillis()

      prepare(source)

      val elapsed = System.currentTimeMillis() - startTime
      if ((debugFlags & DebugOption.Log.flag) != 0) {
        debugLog(s"source prepared: class=${source.getClass.getSimpleName} time=$elapsed")
      }
    }
    normalizeMatchWeight()

    val directScoreContext = new DirectScoreContext(scoreExpr, totalSize, matchWeights, collector)

    sources.foldLeft(0) { (total, source) =>
      val startTime = System.currentTimeMillis()

      val newTotal = execute(source, directScoreContext)

      val elapsed = System.currentTimeMillis() - startTime
      if ((debugFlags & DebugOption.Log.flag) != 0) {
        debugLog(s"source executed: class=${source.getClass.getSimpleName} rows=${newTotal - total} time=$elapsed")
      }
      newTotal
    }

    val startTime = System.currentTimeMillis()

    join(collector)

    val elapsed = System.currentTimeMillis() - startTime

    debugLog(s"engine executed: bufSize=${dataBuffer.numPages * DataBuffer.PAGE_SIZE} joinTime=$elapsed")
  }

  private def prepare(source: ScoreVectorSource): Unit = {
    // if NullExpr, no need to prepare
    if (scoreExpr.isNullExpr) return

    source.prepare(query)
    if (source.weights.nonEmpty) {
      // extract and accumulate information from Weights for later use (percent match)
      accumulateWeightInfo(source.weights)
    } else {
      log.error("no weight created")
    }
  }

  private def execute(source: ScoreVectorSource, directScoreContext: DirectScoreContext): Int = {
    // if NullExpr, no need to execute
    if (scoreExpr.isNullExpr) return dataBuffer.size

    if (source.weights.nonEmpty) {
      source.execute(coreSize, dataBuffer, directScoreContext)
    }
    dataBuffer.size
  }

  private def join(collector: ResultCollector[ScoreContext]): Unit = {
    if (debugTracedIds != null) dumpBuf(debugTracedIds)

    val size = dataBuffer.size
    if (size > 0) {
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
    val debugOption = this
    if (debugTracedIds == null) {
      new JoinerManager(32) {
        def create() = new ScoreContext(scoreExpr, totalSize, matchWeights, collector)
      }
    } else {
      new JoinerManager(32) {
        def create() = {
          val ctx = new ScoreContextWithDebug(scoreExpr, totalSize, matchWeights, collector)
          ctx.debug(debugOption)
          ctx
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
