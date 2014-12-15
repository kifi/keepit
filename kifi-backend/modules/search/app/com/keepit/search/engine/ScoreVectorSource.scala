package com.keepit.search.engine

import com.keepit.common.logging.Logging
import com.keepit.search.engine.explain.{ TargetedScorer, DirectExplainContext }
import com.keepit.search.Searcher
import com.keepit.search.engine.query.core.KWeight
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.util.join.DataBuffer
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.{ Query, Weight, Scorer }
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

trait ScoreVectorSource {
  def prepare(query: Query, matchWeightNormalizer: MatchWeightNormalizer): Unit
  def execute(coreSize: Int, dataBuffer: DataBuffer, directScoreContext: DirectScoreContext): Unit
  def explain(targetId: Long, coreSize: Int, dataBuffer: DataBuffer, directExplainContext: DirectExplainContext): Unit
}

trait ScoreVectorSourceLike extends ScoreVectorSource with Logging with DebugOption {
  protected val weights: ArrayBuffer[(Weight, Float)] = new ArrayBuffer[(Weight, Float)]

  protected def preprocess(query: Query): Query = query

  def prepare(query: Query, matchWeightNormalizer: MatchWeightNormalizer): Unit = {
    weights.clear()
    val weight = searcher.createWeight(preprocess(query))
    if (weight != null) {
      weight.asInstanceOf[KWeight].getWeights(weights)
    }
    if (weights.nonEmpty) {
      // extract and accumulate information from Weights for later use (percent match)
      matchWeightNormalizer.accumulateWeightInfo(weights)
    } else {
      log.error("no weight created")
    }
  }

  def execute(coreSize: Int, dataBuffer: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    if (weights.nonEmpty) {
      val scorers = new Array[Scorer](weights.size)
      indexReaderContexts.foreach { readerContext =>
        var i = 0
        while (i < scorers.length) {
          scorers(i) = weights(i)._1.scorer(readerContext, readerContext.reader.getLiveDocs)
          i += 1
        }
        writeScoreVectors(readerContext, scorers, coreSize, dataBuffer, directScoreContext)
      }
    }
  }

  def explain(targetId: Long, coreSize: Int, dataBuffer: DataBuffer, directExplainContext: DirectExplainContext): Unit = {
    if (weights.nonEmpty) {
      val scorers = new Array[Scorer](weights.size)
      indexReaderContexts.foreach { readerContext =>
        var i = 0
        while (i < scorers.length) {
          scorers(i) = TargetedScorer(readerContext, weights(i)._1, targetId, idResolver(readerContext))
          i += 1
        }
        writeScoreVectors(readerContext, scorers, coreSize, dataBuffer, directExplainContext)
      }
    }
  }

  protected def idResolver(readerContext: AtomicReaderContext): TargetedScorer.Resolver = {
    idMapperBasedIdResolver(readerContext)
  }

  protected def idMapperBasedIdResolver(readerContext: AtomicReaderContext): TargetedScorer.Resolver = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idMapper = reader.getIdMapper
    new TargetedScorer.Resolver {
      def apply(docId: Int) = idMapper.getId(docId)
    }
  }

  protected def docValueBasedIdResolver(readerContext: AtomicReaderContext, idField: String): TargetedScorer.Resolver = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idDocValues = reader.getNumericDocValues(idField)
    new TargetedScorer.Resolver {
      def apply(docId: Int) = idDocValues.get(docId)
    }
  }

  protected val searcher: Searcher

  protected def indexReaderContexts: Seq[AtomicReaderContext] = { searcher.indexReader.getContext.leaves }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext)

  protected def createScorerQueue(scorers: Array[Scorer], coreSize: Int): TaggedScorerQueue = TaggedScorerQueue(scorers, coreSize)
}

