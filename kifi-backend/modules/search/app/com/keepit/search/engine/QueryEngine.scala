package com.keepit.search.engine

import com.keepit.common.logging.Logging
import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBufferReader, AggregationContextManager, DataBuffer, HashJoin }
import org.apache.lucene.search.{ Query, Weight }

import scala.collection.mutable.ListBuffer

class QueryEngine private[engine] (scoreExpr: ScoreExpr, query: Query, totalSize: Int, coreSize: Int, val recencyOnly: Boolean) extends Logging with DebugOption {

  private[this] val dataBuffer: DataBuffer = new DataBuffer()
  private[this] val matchWeightNormalizer: MatchWeightNormalizer = new MatchWeightNormalizer(coreSize)

  def execute(collector: ResultCollector[ScoreContext], sources: ScoreVectorSource*): Unit = {

    // if NullExpr, no need to execute
    if (scoreExpr.isNullExpr) {
      debugLog("engine not executed: NullExpr")
      return
    }

    sources.foreach(prepare(_))

    matchWeightNormalizer.normalizeMatchWeight()
    val directScoreContext = createDirectScoreContext(collector)

    sources.foreach(execute(_, directScoreContext))

    join(createScoreContextManager(collector))

    if ((debugFlags & DebugOption.Log.flag) != 0) {
      debugLog(s"engine executed: pages=${dataBuffer.numPages} rows=${dataBuffer.size} bytes=${dataBuffer.numPages * DataBuffer.PAGE_SIZE} direct=${directScoreContext.getCount}")
    }
  }

  private[this] def prepare(source: ScoreVectorSource): Unit = {
    val startTime = System.currentTimeMillis()

    source.prepare(query, matchWeightNormalizer)

    val elapsed = System.currentTimeMillis() - startTime
    if ((debugFlags & DebugOption.Log.flag) != 0) {
      debugLog(s"source prepared: class=${source.getClass.getSimpleName} time=$elapsed")
    }
  }

  private[this] def execute(source: ScoreVectorSource, directScoreContext: DirectScoreContext): Int = {
    val startTime = System.currentTimeMillis()

    val lastTotal = dataBuffer.size
    source.execute(coreSize, dataBuffer, directScoreContext)
    val newTotal = dataBuffer.size

    val elapsed = System.currentTimeMillis() - startTime
    if ((debugFlags & DebugOption.Log.flag) != 0) {
      debugLog(s"source executed: class=${source.getClass.getSimpleName} rows=${newTotal - lastTotal} time=$elapsed")
    }
    newTotal
  }

  private[this] def join(aggregationContextManager: AggregationContextManager): Unit = {
    val startTime = System.currentTimeMillis()

    if (debugTracedIds != null) dumpBuf(debugTracedIds)

    val size = dataBuffer.size
    if (size > 0) {
      val numBuckets = ((size / 10 + 1) | 0x01)
      val hashJoin = new HashJoin(dataBuffer, numBuckets, aggregationContextManager)
      hashJoin.execute()
    }

    val elapsed = System.currentTimeMillis() - startTime
    debugLog(s"engine joined: time=$elapsed")
  }

  def getScoreExpr(): ScoreExpr = scoreExpr
  def getQuery(): Query = query
  def getTotalSize(): Int = totalSize
  def getCoreSize(): Int = coreSize
  def getMatchWeightNormalizer(): MatchWeightNormalizer = matchWeightNormalizer

  private[this] def createDirectScoreContext(collector: ResultCollector[ScoreContext]): DirectScoreContext = {
    val debugOption = this
    if (debugTracedIds == null) {
      new DirectScoreContext(scoreExpr, totalSize, matchWeightNormalizer.get, collector)
    } else {
      val ctx = new DirectScoreContextWithDebug(scoreExpr, totalSize, matchWeightNormalizer.get, collector)
      ctx.debug(debugOption)
      ctx
    }
  }

  private[this] def createScoreContextManager(collector: ResultCollector[ScoreContext]): AggregationContextManager = {
    val debugOption = this
    if (debugTracedIds == null) {
      new AggregationContextManager(32) {
        def create() = new ScoreContext(scoreExpr, totalSize, matchWeightNormalizer.get, collector)
      }
    } else {
      new AggregationContextManager(32) {
        def create() = {
          val ctx = new ScoreContextWithDebug(scoreExpr, totalSize, matchWeightNormalizer.get, collector)
          ctx.debug(debugOption)
          ctx
        }
      }
    }
  }

  private[this] def dumpBuf(ids: Set[Long]): Unit = {
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
        debugLog(s"databuf offset=${reader.recordOffset} id=$id id2=$id2 visibility=[${Visibility.toString(reader.recordType)}] scores=${scores}")
      }
    }
  }
}

class MatchWeightNormalizer(coreSize: Int) {
  private[this] val matchWeights: Array[Float] = new Array[Float](coreSize)

  def get: Array[Float] = matchWeights

  def accumulateWeightInfo(weights: IndexedSeq[(Weight, Float)]): Unit = synchronized {
    var i = 0
    while (i < coreSize) {
      matchWeights(i) += weights(i)._2
      i += 1
    }
  }

  def normalizeMatchWeight(): Unit = synchronized {
    var sum = 0.0f
    var i = 0
    while (i < coreSize) {
      sum += matchWeights(i)
      i += 1
    }
    if (sum != 0.0f) {
      i = 0
      while (i < coreSize) {
        matchWeights(i) = matchWeights(i) / sum
        i += 1
      }
    }
  }

}
